import fs from 'fs';
import path from 'path';

/**
 * Store for chat messages and sync state.
 *
 * Primary backend is SQLite (better-sqlite3). If the native binding cannot be
 * loaded on this platform we transparently fall back to an append-only JSONL
 * file, so the server still runs — messages stay unbounded either way, which is
 * the "unlimited" promise applied to history as well as to files.
 */

const SCHEMA = `
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  model TEXT,
  plan TEXT DEFAULT 'unlimited',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  message_count INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, seq);
CREATE TABLE IF NOT EXISTS sync_state (
  session_id TEXT PRIMARY KEY,
  remote_id TEXT,
  backend TEXT,
  cursor INTEGER NOT NULL DEFAULT 0,
  last_pushed_at TEXT,
  last_pulled_at TEXT,
  pushed_files INTEGER NOT NULL DEFAULT 0,
  pulled_files INTEGER NOT NULL DEFAULT 0
);
`;

export class SessionStore {
  constructor({ file, driver = 'memory', db = null }) {
    this.file = file;
    this.driver = driver;
    this.db = db;
    this.memory = { sessions: new Map(), messages: [], sync: new Map() };
  }

  /**
   * async factory. Preference order:
   *   1. better-sqlite3  — fastest, but a native module (needs node-gyp)
   *   2. node:sqlite     — built into Node 22+, no compilation
   *   3. jsonl           — last resort, still unbounded history
   */
  static async open({ file }) {
    fs.mkdirSync(path.dirname(file), { recursive: true });

    try {
      const mod = await import('better-sqlite3');
      const Database = mod.default || mod;
      const db = new Database(file);
      db.pragma('journal_mode = WAL');
      db.exec(SCHEMA);
      return new SessionStore({ file, driver: 'sqlite', db });
    } catch (err) {
      try {
        const { loadNodeSqlite } = await import('./node-sqlite-adapter.js');
        const open = await loadNodeSqlite();
        const db = open(file);
        db.exec(SCHEMA);
        const store = new SessionStore({ file, driver: 'node:sqlite', db });
        store.loadError = `better-sqlite3 tidak tersedia (${err.code || err.message}); memakai node:sqlite`;
        return store;
      } catch (inner) {
        const store = new SessionStore({ file: file.replace(/\.sqlite3?$/, '.jsonl'), driver: 'jsonl' });
        store.loadJsonl();
        store.loadError = `sqlite tidak tersedia (${inner.message}); memakai jsonl`;
        return store;
      }
    }
  }

  // ----------------------------------------------------------------- sessions

  upsertSession(meta) {
    if (this.db) {
      this.db
        .prepare(
          `INSERT INTO sessions (id, title, model, plan, created_at, updated_at, message_count)
           VALUES (@id, @title, @model, @plan, @createdAt, @updatedAt, @messageCount)
           ON CONFLICT(id) DO UPDATE SET title=@title, model=@model, plan=@plan,
             updated_at=@updatedAt, message_count=@messageCount`,
        )
        .run({
          id: meta.id,
          title: meta.title,
          model: meta.model || null,
          plan: meta.plan || 'unlimited',
          createdAt: meta.createdAt,
          updatedAt: meta.updatedAt,
          messageCount: meta.messageCount || 0,
        });
      return;
    }
    this.memory.sessions.set(meta.id, { ...meta });
    this.flushJsonl();
  }

  touchSession(id, patch = {}) {
    const updatedAt = new Date().toISOString();
    if (this.db) {
      const row = this.db.prepare('SELECT * FROM sessions WHERE id = ?').get(id);
      if (!row) return;
      this.db
        .prepare('UPDATE sessions SET title=?, model=?, updated_at=?, message_count=? WHERE id=?')
        .run(
          patch.title ?? row.title,
          patch.model ?? row.model,
          updatedAt,
          patch.messageCount ?? row.message_count,
          id,
        );
      return;
    }
    const s = this.memory.sessions.get(id);
    if (!s) return;
    Object.assign(s, {
      title: patch.title ?? s.title,
      model: patch.model ?? s.model,
      messageCount: patch.messageCount ?? s.messageCount,
      updatedAt,
    });
    this.flushJsonl();
  }

  countMessages(sessionId) {
    if (this.db) {
      return this.db.prepare('SELECT COUNT(*) AS n FROM messages WHERE session_id = ?').get(sessionId).n;
    }
    return this.memory.messages.filter((m) => m.sessionId === sessionId).length;
  }

  deleteSession(sessionId) {
    if (this.db) {
      this.db.prepare('DELETE FROM messages WHERE session_id = ?').run(sessionId);
      this.db.prepare('DELETE FROM sync_state WHERE session_id = ?').run(sessionId);
      this.db.prepare('DELETE FROM sessions WHERE id = ?').run(sessionId);
      return;
    }
    this.memory.sessions.delete(sessionId);
    this.memory.messages = this.memory.messages.filter((m) => m.sessionId !== sessionId);
    this.memory.sync.delete(sessionId);
    this.flushJsonl();
  }

  // ----------------------------------------------------------------- messages

  appendMessage(sessionId, message) {
    const seq = this.countMessages(sessionId) + 1;
    const createdAt = new Date().toISOString();
    const content = JSON.stringify(message.content);
    if (this.db) {
      this.db
        .prepare('INSERT INTO messages (session_id, seq, role, content, created_at) VALUES (?, ?, ?, ?, ?)')
        .run(sessionId, seq, message.role, content, createdAt);
    } else {
      this.memory.messages.push({ sessionId, seq, role: message.role, content, createdAt });
      this.flushJsonl();
    }
    this.touchSession(sessionId, { messageCount: seq });
    return { sessionId, seq, role: message.role, content: message.content, createdAt };
  }

  listMessages(sessionId) {
    const rows = this.db
      ? this.db.prepare('SELECT * FROM messages WHERE session_id = ? ORDER BY seq ASC').all(sessionId)
      : this.memory.messages.filter((m) => m.sessionId === sessionId).sort((a, b) => a.seq - b.seq);
    return rows.map((r) => ({
      seq: r.seq,
      role: r.role,
      content: JSON.parse(r.content),
      createdAt: r.created_at || r.createdAt,
    }));
  }

  /** Stored messages in provider wire format. */
  providerMessages(sessionId) {
    return this.listMessages(sessionId).map((m) => ({ role: m.role, content: m.content }));
  }

  // --------------------------------------------------------------------- sync

  getSyncState(sessionId) {
    if (this.db) {
      const row = this.db.prepare('SELECT * FROM sync_state WHERE session_id = ?').get(sessionId);
      return row ? normaliseSync(row) : emptySync(sessionId);
    }
    return this.memory.sync.get(sessionId) || emptySync(sessionId);
  }

  setSyncState(sessionId, patch) {
    const next = { ...this.getSyncState(sessionId), ...patch, sessionId };
    if (this.db) {
      this.db
        .prepare(
          `INSERT INTO sync_state (session_id, remote_id, backend, cursor, last_pushed_at, last_pulled_at, pushed_files, pulled_files)
           VALUES (@sessionId, @remoteId, @backend, @cursor, @lastPushedAt, @lastPulledAt, @pushedFiles, @pulledFiles)
           ON CONFLICT(session_id) DO UPDATE SET remote_id=@remoteId, backend=@backend, cursor=@cursor,
             last_pushed_at=@lastPushedAt, last_pulled_at=@lastPulledAt,
             pushed_files=@pushedFiles, pulled_files=@pulledFiles`,
        )
        .run({
          sessionId: next.sessionId,
          remoteId: next.remoteId || null,
          backend: next.backend || null,
          cursor: next.cursor || 0,
          lastPushedAt: next.lastPushedAt || null,
          lastPulledAt: next.lastPulledAt || null,
          pushedFiles: next.pushedFiles || 0,
          pulledFiles: next.pulledFiles || 0,
        });
      return next;
    }
    this.memory.sync.set(sessionId, next);
    this.flushJsonl();
    return next;
  }

  // --------------------------------------------------------------------- jsonl

  loadJsonl() {
    try {
      const raw = fs.readFileSync(this.file, 'utf8');
      const parsed = JSON.parse(raw);
      for (const s of parsed.sessions || []) this.memory.sessions.set(s.id, s);
      this.memory.messages = parsed.messages || [];
      for (const [k, v] of Object.entries(parsed.sync || {})) this.memory.sync.set(k, v);
    } catch {
      /* first run */
    }
  }

  flushJsonl() {
    if (this.driver !== 'jsonl') return;
    const payload = {
      sessions: [...this.memory.sessions.values()],
      messages: this.memory.messages,
      sync: Object.fromEntries(this.memory.sync),
    };
    fs.writeFileSync(this.file, JSON.stringify(payload));
  }

  close() {
    if (this.db) this.db.close();
  }
}

function normaliseSync(row) {
  return {
    sessionId: row.session_id,
    remoteId: row.remote_id,
    backend: row.backend,
    cursor: row.cursor,
    lastPushedAt: row.last_pushed_at,
    lastPulledAt: row.last_pulled_at,
    pushedFiles: row.pushed_files,
    pulledFiles: row.pulled_files,
  };
}

function emptySync(sessionId) {
  return { sessionId, cursor: 0, pushedFiles: 0, pulledFiles: 0, backend: null, remoteId: null };
}
