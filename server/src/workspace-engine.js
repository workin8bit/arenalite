import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { getPlan, evaluate, describe } from './quota.js';

const fsp = fs.promises;

export const MAX_READ_BYTES = 1024 * 1024; // 1 MB shown in the viewer; more via ?raw=1
export const MAX_EXEC_OUTPUT = 64 * 1024;

export function nowIso() {
  return new Date().toISOString();
}

export function shortId(bytes = 6) {
  return crypto.randomBytes(bytes).toString('hex');
}

/** Deterministic content hash used by the sync engine for delta detection. */
export function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

/**
 * On-disk layout, rooted at dataRoot:
 *
 *   <dataRoot>/
 *     sessions/<sessionId>/files/     <- the workspace the agent edits
 *     sessions/<sessionId>/meta.json
 *     sessions/<sessionId>/oplog.jsonl
 *     sessions/<sessionId>/snapshots/
 *     sync/remote/<sessionId>/...     <- the local stand-in for cloud storage
 */
export class WorkspaceEngine {
  constructor({ dataRoot, planId = 'unlimited' }) {
    this.dataRoot = path.resolve(dataRoot);
    this.plan = getPlan(planId);
    fs.mkdirSync(path.join(this.dataRoot, 'sessions'), { recursive: true });
    fs.mkdirSync(path.join(this.dataRoot, 'sync', 'remote'), { recursive: true });
  }

  sessionDir(sessionId) {
    return path.join(this.dataRoot, 'sessions', sessionId);
  }

  rootFor(sessionId) {
    return path.join(this.sessionDir(sessionId), 'files');
  }

  /**
   * Resolve an untrusted relative path inside a session workspace.
   * Rejects traversal instead of silently clamping, so a confused agent gets a
   * clear error rather than writing outside its sandbox.
   */
  resolve(sessionId, relPath = '.') {
    const root = this.rootFor(sessionId);
    const clean = path.posix.normalize(String(relPath).replace(/\\/g, '/')).replace(/^\/+/, '');
    const abs = path.resolve(root, clean);
    if (abs !== root && !abs.startsWith(root + path.sep)) {
      const err = new Error(`Path keluar dari workspace: ${relPath}`);
      err.code = 'EPATHOUTSIDE';
      throw err;
    }
    return abs;
  }

  // ---------------------------------------------------------------- lifecycle

  async createSession({ title = 'Workspace baru', model = 'arena-agent-1', planId } = {}) {
    const id = `ws_${nowIso().slice(0, 10).replace(/-/g, '')}_${shortId(4)}`;
    const dir = this.sessionDir(id);
    await fsp.mkdir(path.join(dir, 'files'), { recursive: true });
    await fsp.mkdir(path.join(dir, 'snapshots'), { recursive: true });
    const meta = {
      id,
      title,
      model,
      plan: planId || this.plan.id,
      createdAt: nowIso(),
      updatedAt: nowIso(),
      messageCount: 0,
      status: 'idle',
    };
    await fsp.writeFile(path.join(dir, 'meta.json'), JSON.stringify(meta, null, 2));
    await this.writeFile(id, 'README.md', seedReadme(title));
    await this.appendOp(id, { type: 'session.create', path: null, detail: { title } });
    return meta;
  }

  async readMeta(sessionId) {
    const file = path.join(this.sessionDir(sessionId), 'meta.json');
    if (!fs.existsSync(file)) {
      const err = new Error(`Workspace ${sessionId} tidak ditemukan`);
      err.code = 'ENOSESSION';
      throw err;
    }
    return JSON.parse(await fsp.readFile(file, 'utf8'));
  }

  async writeMeta(sessionId, patch) {
    const meta = { ...(await this.readMeta(sessionId)), ...patch, updatedAt: nowIso() };
    await fsp.writeFile(path.join(this.sessionDir(sessionId), 'meta.json'), JSON.stringify(meta, null, 2));
    return meta;
  }

  async listSessions() {
    const base = path.join(this.dataRoot, 'sessions');
    const entries = await fsp.readdir(base, { withFileTypes: true }).catch(() => []);
    const out = [];
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      try {
        const meta = await this.readMeta(entry.name);
        out.push(meta);
      } catch {
        /* half-written workspace, skip */
      }
    }
    out.sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1));
    return out;
  }

  /**
   * "Unlimited" also means unlimited clones: forking a workspace copies the
   * files and rewrites the oplog cursor so the copy syncs independently.
   */
  async forkSession(sessionId, { title } = {}) {
    const src = await this.readMeta(sessionId);
    const copy = await this.createSession({
      title: title || `${src.title} (copy)`,
      model: src.model,
    });
    await copyDir(this.rootFor(sessionId), this.rootFor(copy.id));
    await this.appendOp(copy.id, { type: 'session.fork', path: null, detail: { from: sessionId } });
    return copy;
  }

  async deleteSession(sessionId) {
    await this.readMeta(sessionId); // throws when unknown
    await fsp.rm(this.sessionDir(sessionId), { recursive: true, force: true });
    return { id: sessionId, deleted: true };
  }

  // -------------------------------------------------------------------- files

  async listFiles(sessionId, relPath = '.') {
    const abs = this.resolve(sessionId, relPath);
    const entries = await fsp.readdir(abs, { withFileTypes: true });
    // Normalise the listing root so entries always carry a workspace-relative
    // POSIX path ("src/index.js"), never a leading slash — clients paste these
    // straight back into /files/<path> and into the chat tools.
    const relRoot = path.posix.normalize(String(relPath).replace(/\\/g, '/')).replace(/^\/+/, '');
    const prefix = relRoot === '.' || relRoot === '' ? '' : relRoot;
    const items = await Promise.all(
      entries.map(async (entry) => {
        const full = path.join(abs, entry.name);
        const st = await fsp.stat(full).catch(() => null);
        return {
          name: entry.name,
          path: prefix ? `${prefix}/${entry.name}` : entry.name,
          type: entry.isDirectory() ? 'dir' : 'file',
          size: st ? st.size : 0,
          modifiedAt: st ? st.mtime.toISOString() : null,
        };
      }),
    );
    items.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1));
    return items;
  }

  async readFile(sessionId, relPath) {
    const abs = this.resolve(sessionId, relPath);
    const st = await fsp.stat(abs);
    if (st.isDirectory()) {
      const err = new Error(`${relPath} adalah direktori`);
      err.code = 'EISDIR';
      throw err;
    }
    const buf = await fsp.readFile(abs);
    return {
      path: relPath,
      size: buf.length,
      truncated: buf.length > MAX_READ_BYTES,
      base64: buf.toString('base64'),
      text: isProbablyText(buf) ? buf.subarray(0, MAX_READ_BYTES).toString('utf8') : null,
      hash: sha256(buf),
      modifiedAt: st.mtime.toISOString(),
    };
  }

  /**
   * The single write path. Everything (agent tool, REST API, sync pull) goes
   * through here so the oplog + usage accounting can never drift.
   */
  async writeFile(sessionId, relPath, content, { mode = 0o644, source = 'api' } = {}) {
    const usage = await this.usage(sessionId);
    const incoming = Buffer.byteLength(content);
    const decision = evaluate(this.plan, usage, { incomingBytes: incoming });
    if (!decision.ok) {
      const err = new Error('Kuota terlampaui');
      err.code = 'EQUOTA';
      throw err;
    }

    const abs = this.resolve(sessionId, relPath);
    const previous = fs.existsSync(abs) ? await fsp.readFile(abs).catch(() => null) : null;
    await fsp.mkdir(path.dirname(abs), { recursive: true });
    await fsp.writeFile(abs, content, { mode });

    const stat = await fsp.stat(abs);
    const entry = {
      type: 'file.write',
      path: relPath,
      source,
      at: nowIso(),
      detail: {
        bytes: incoming,
        hash: sha256(Buffer.from(content)),
        previousHash: previous ? sha256(previous) : null,
        previousSize: previous ? previous.length : 0,
        created: previous === null,
      },
    };
    await this.appendOp(sessionId, entry);
    await this.writeMeta(sessionId, {});
    return { path: relPath, bytes: incoming, created: previous === null, size: stat.size, warnings: decision.warnings };
  }

  async deletePath(sessionId, relPath, { source = 'api' } = {}) {
    const abs = this.resolve(sessionId, relPath);
    if (abs === this.rootFor(sessionId)) {
      const err = new Error('Tidak bisa menghapus akar workspace');
      err.code = 'EROOT';
      throw err;
    }
    const existed = fs.existsSync(abs);
    await fsp.rm(abs, { recursive: true, force: true });
    await this.appendOp(sessionId, { type: 'file.delete', path: relPath, source, at: nowIso(), detail: { existed } });
    return { path: relPath, deleted: existed };
  }

  async mkdir(sessionId, relPath, { source = 'api' } = {}) {
    const abs = this.resolve(sessionId, relPath);
    await fsp.mkdir(abs, { recursive: true });
    await this.appendOp(sessionId, { type: 'dir.create', path: relPath, source, at: nowIso(), detail: {} });
    return { path: relPath, created: true };
  }

  async movePath(sessionId, from, to, { source = 'api' } = {}) {
    const src = this.resolve(sessionId, from);
    const dst = this.resolve(sessionId, to);
    await fsp.mkdir(path.dirname(dst), { recursive: true });
    await fsp.rename(src, dst);
    await this.appendOp(sessionId, { type: 'file.move', path: to, source, at: nowIso(), detail: { from } });
    return { from, to };
  }

  /** Copy a whole tree — used by snapshots and by forkSession. */
  async snapshot(sessionId, label = `snap_${shortId(3)}`) {
    const dest = path.join(this.sessionDir(sessionId), 'snapshots', label);
    await fsp.mkdir(dest, { recursive: true });
    await copyDir(this.rootFor(sessionId), dest);
    const files = await walk(dest);
    await this.appendOp(sessionId, {
      type: 'snapshot.create',
      path: null,
      source: 'system',
      at: nowIso(),
      detail: { label, files: files.length },
    });
    return { label, files: files.length };
  }

  // -------------------------------------------------------------- usage / ops

  /**
   * Measured, never limited. Returns Infinity for the plan caps so the UI can
   * print "∞" without special casing.
   */
  async usage(sessionId) {
    // readMeta first so an unknown/deleted workspace is a 404, not a silent
    // "0 files" answer that looks like a real empty workspace.
    const meta = await this.readMeta(sessionId);
    const root = this.rootFor(sessionId);
    const files = await walk(root);
    let totalBytes = 0;
    let largest = 0;
    for (const f of files) totalBytes += f.size;
    for (const f of files) largest = Math.max(largest, f.size);
    return {
      sessionId,
      files: files.length,
      directories: await countDirs(root),
      totalBytes,
      largestFileBytes: largest,
      messageCount: meta.messageCount || 0,
      plan: describe(this.plan),
    };
  }

  async globalUsage() {
    const sessions = await this.listSessions();
    let totalBytes = 0;
    let files = 0;
    for (const s of sessions) {
      const u = await this.usage(s.id);
      totalBytes += u.totalBytes;
      files += u.files;
    }
    return {
      workspaces: sessions.length,
      files,
      totalBytes,
      plan: describe(this.plan),
    };
  }

  async appendOp(sessionId, op) {
    const record = { seq: await this.nextSeq(sessionId), ...op, at: op.at || nowIso() };
    const file = path.join(this.sessionDir(sessionId), 'oplog.jsonl');
    await fsp.appendFile(file, JSON.stringify(record) + '\n');
    return record;
  }

  async nextSeq(sessionId) {
    const file = path.join(this.sessionDir(sessionId), 'seq');
    let n = 0;
    try {
      n = parseInt(await fsp.readFile(file, 'utf8'), 10) || 0;
    } catch {
      n = 0;
    }
    n += 1;
    await fsp.writeFile(file, String(n));
    return n;
  }

  /**
   * Delta feed for the sync engine and the web client's activity panel.
   * `cursor` is the last seq the caller has seen; 0 means "everything".
   */
  async opsSince(sessionId, cursor = 0) {
    const file = path.join(this.sessionDir(sessionId), 'oplog.jsonl');
    const raw = await fsp.readFile(file, 'utf8').catch(() => '');
    const ops = raw
      .split('\n')
      .filter(Boolean)
      .map((line) => {
        try {
          return JSON.parse(line);
        } catch {
          return null;
        }
      })
      .filter((o) => o && o.seq > cursor);
    return { cursor: ops.length ? ops[ops.length - 1].seq : cursor, ops };
  }

  /** Materialised file list with hashes — what the sync engine diffs against. */
  async manifest(sessionId) {
    const root = this.rootFor(sessionId);
    const files = await walk(root);
    const out = [];
    for (const f of files) {
      const rel = path.relative(root, f.path).split(path.sep).join('/');
      const buf = await fsp.readFile(f.path);
      out.push({ path: rel, size: f.size, hash: sha256(buf), modifiedAt: f.mtime });
    }
    out.sort((a, b) => a.path.localeCompare(b.path));
    return { sessionId, generatedAt: nowIso(), files: out };
  }
}

// ------------------------------------------------------------------- helpers

export async function walk(dir) {
  const out = [];
  const stack = [dir];
  while (stack.length) {
    const current = stack.pop();
    const entries = await fsp.readdir(current, { withFileTypes: true }).catch(() => []);
    for (const e of entries) {
      const full = path.join(current, e.name);
      if (e.isDirectory()) stack.push(full);
      else if (e.isFile()) {
        const st = await fsp.stat(full).catch(() => null);
        if (st) out.push({ path: full, size: st.size, mtime: st.mtime.toISOString() });
      }
    }
  }
  return out;
}

async function countDirs(dir) {
  let n = 0;
  const stack = [dir];
  while (stack.length) {
    const current = stack.pop();
    const entries = await fsp.readdir(current, { withFileTypes: true }).catch(() => []);
    for (const e of entries) {
      if (e.isDirectory()) {
        n += 1;
        stack.push(path.join(current, e.name));
      }
    }
  }
  return n;
}

async function copyDir(src, dest) {
  await fsp.mkdir(dest, { recursive: true });
  const entries = await fsp.readdir(src, { withFileTypes: true }).catch(() => []);
  for (const e of entries) {
    const s = path.join(src, e.name);
    const d = path.join(dest, e.name);
    if (e.isDirectory()) await copyDir(s, d);
    else await fsp.copyFile(s, d);
  }
}

export function isProbablyText(buf) {
  const sample = buf.subarray(0, 512);
  if (sample.length === 0) return true;
  let suspicious = 0;
  for (const byte of sample) if (byte === 0) suspicious += 1;
  return suspicious === 0;
}

export function seedReadme(title) {
  return `# ${title}

Workspace ini **tidak punya kuota**: simpan file sebesar apa pun, buat session sebanyak apa pun.

- \`files/\` di server = isi workspace ini
- Setiap perubahan dicatat di \`oplog.jsonl\` dan bisa di-sinkronkan
- Agent punya tools: read_file, write_file, edit_file, list_files, delete_file, exec

Coba minta agent: "buatkan script python yang menghitung fibonacci lalu jalankan".
`;
}

