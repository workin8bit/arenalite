import { useEffect, useRef, useState } from 'react';
import { formatBytes, formatLimit, formatTime, truncate } from '../util/format.js';
import { api, terminalUrl } from '../api/client.js';

const TABS = [
  { id: 'files', label: 'File' },
  { id: 'terminal', label: 'Terminal' },
  { id: 'activity', label: 'Aktivitas' },
  { id: 'usage', label: 'Pemakaian' },
];

export default function SidePanel({ sessionId, usage, sync, onNotify, refreshKey }) {
  const [tab, setTab] = useState('files');

  return (
    <aside className="panel">
      <div className="panel-tabs">
        {TABS.map((t) => (
          <button key={t.id} className={`panel-tab ${tab === t.id ? 'active' : ''}`} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
      </div>
      <div className="panel-body">
        {tab === 'files' && <FilesTab sessionId={sessionId} onNotify={onNotify} refreshKey={refreshKey} />}
        {tab === 'terminal' && <TerminalTab sessionId={sessionId} />}
        {tab === 'activity' && <ActivityTab sessionId={sessionId} refreshKey={refreshKey} />}
        {tab === 'usage' && <UsageTab usage={usage} sync={sync} sessionId={sessionId} onNotify={onNotify} />}
      </div>
    </aside>
  );
}

function FilesTab({ sessionId, onNotify, refreshKey }) {
  const [path, setPath] = useState('.');
  const [entries, setEntries] = useState([]);
  const [file, setFile] = useState(null);
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState(false);

  const load = async (target) => {
    if (!sessionId) return;
    try {
      const res = await api.listFiles(sessionId, target);
      setEntries(res.entries);
    } catch (err) {
      onNotify?.(err.message, true);
    }
  };

  useEffect(() => {
    setFile(null);
    setEditing(false);
    load(path);
  }, [sessionId, path, refreshKey]);

  const openFile = async (entry) => {
    try {
      const res = await api.readFile(sessionId, entry.path);
      setFile(res);
      setDraft(res.text ?? '');
      setEditing(false);
    } catch (err) {
      onNotify?.(err.message, true);
    }
  };

  const save = async () => {
    try {
      await api.writeFile(sessionId, file.path, draft);
      onNotify?.(`${file.path} disimpan`);
      setEditing(false);
      const res = await api.readFile(sessionId, file.path);
      setFile(res);
    } catch (err) {
      onNotify?.(err.message, true);
    }
  };

  if (!sessionId) return null;

  if (file) {
    return (
      <div className="viewer">
        <div className="viewer-head">
          <button className="icon-button" onClick={() => setFile(null)}>
            ←
          </button>
          <span style={{ fontFamily: 'var(--mono)' }}>{file.path}</span>
          <span style={{ marginLeft: 'auto', color: 'var(--text-faint)' }}>{formatBytes(file.size)}</span>
          {file.text !== null && (
            <button className="icon-button" onClick={() => (editing ? save() : setEditing(true))}>
              {editing ? 'simpan' : 'edit'}
            </button>
          )}
        </div>
        {editing ? (
          <textarea value={draft} onChange={(e) => setDraft(e.target.value)} spellCheck={false} />
        ) : (
          <pre>{file.text ?? `[biner ${formatBytes(file.size)}]`}</pre>
        )}
      </div>
    );
  }

  const crumbs = path === '.' ? [] : path.split('/').filter(Boolean);

  return (
    <div>
      <div className="viewer-head">
        <button className="icon-button" onClick={() => setPath(crumbs.length > 1 ? crumbs.slice(0, -1).join('/') : '.')}>
          ↑
        </button>
        <span style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{path === '.' ? 'workspace/' : `${path}/`}</span>
      </div>
      {entries.map((entry) => (
        <button
          key={entry.path}
          className="file-row"
          onClick={() => (entry.type === 'dir' ? setPath(entry.path) : openFile(entry))}
        >
          <span>{entry.type === 'dir' ? '📁' : '📄'}</span>
          <span>{entry.name}</span>
          <span className="size">{entry.type === 'dir' ? '' : formatBytes(entry.size)}</span>
        </button>
      ))}
      {entries.length === 0 && <div style={{ padding: 16, color: 'var(--text-faint)', fontSize: 12 }}>Folder kosong.</div>}
    </div>
  );
}

function TerminalTab({ sessionId }) {
  const [lines, setLines] = useState([]);
  const [command, setCommand] = useState('');
  const [connected, setConnected] = useState(false);
  const wsRef = useRef(null);
  const preRef = useRef(null);

  useEffect(() => {
    if (!sessionId) return undefined;
    setLines([`menghubungkan ke workspace…`]);
    const ws = new WebSocket(terminalUrl(sessionId));
    wsRef.current = ws;
    ws.onopen = () => {
      setConnected(true);
      setLines(['$ shell siap di dalam workspace. Ketik perintah lalu Enter.']);
    };
    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'output') setLines((prev) => [...prev, msg.data.replace(/\n$/, '')]);
        if (msg.type === 'ready') setLines((prev) => [...prev, `[${msg.driver}] cwd: ${msg.cwd}`]);
        if (msg.type === 'exit') setLines((prev) => [...prev, `[shell keluar: ${msg.exitCode}]`]);
      } catch {
        /* ignore */
      }
    };
    ws.onclose = () => {
      setConnected(false);
      setLines((prev) => [...prev, '[koneksi ditutup]']);
    };
    return () => ws.close();
  }, [sessionId]);

  useEffect(() => {
    const node = preRef.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [lines]);

  const submit = (event) => {
    event.preventDefault();
    if (!command.trim() || wsRef.current?.readyState !== 1) return;
    setLines((prev) => [...prev, `$ ${command}`]);
    wsRef.current.send(JSON.stringify({ type: 'input', data: command }));
    setCommand('');
  };

  return (
    <div className="terminal">
      <pre ref={preRef}>{truncate(lines.join('\n'), 20000)}</pre>
      <form onSubmit={submit}>
        <input
          value={command}
          onChange={(e) => setCommand(e.target.value)}
          placeholder={connected ? 'jalankan perintah…' : 'terputus'}
          spellCheck={false}
        />
        <button className="icon-button" type="submit">
          ⏎
        </button>
      </form>
    </div>
  );
}

function ActivityTab({ sessionId, refreshKey }) {
  const [ops, setOps] = useState([]);

  useEffect(() => {
    if (!sessionId) return;
    api
      .ops(sessionId, 0)
      .then((res) => setOps(res.ops.slice(-120).reverse()))
      .catch(() => setOps([]));
  }, [sessionId, refreshKey]);

  return (
    <div>
      {ops.map((op) => (
        <div className="op-row" key={op.seq}>
          <div className="op-type">
            #{op.seq} {op.type}
          </div>
          {op.path && <div className="op-path">{op.path}</div>}
          <div className="op-time">
            {op.source} · {formatTime(op.at)}
            {op.detail?.bytes ? ` · ${formatBytes(op.detail.bytes)}` : ''}
            {op.detail?.exitCode !== undefined ? ` · exit ${op.detail.exitCode}` : ''}
          </div>
        </div>
      ))}
      {ops.length === 0 && <div style={{ padding: 16, color: 'var(--text-faint)', fontSize: 12 }}>Belum ada aktivitas.</div>}
    </div>
  );
}

function UsageTab({ usage, sync, sessionId, onNotify }) {
  const [busy, setBusy] = useState(false);

  const run = async (fn, label) => {
    setBusy(true);
    try {
      await fn();
      onNotify?.(label);
    } catch (err) {
      onNotify?.(err.message, true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="usage-box">
      <div className="usage-line">
        <span>File</span>
        <b>{usage?.files ?? 0}</b>
      </div>
      <div className="usage-line">
        <span>Direktori</span>
        <b>{usage?.directories ?? 0}</b>
      </div>
      <div className="usage-line">
        <span>Total</span>
        <b>{formatBytes(usage?.totalBytes ?? 0)}</b>
      </div>
      <div className="usage-line">
        <span>File terbesar</span>
        <b>{formatBytes(usage?.largestFileBytes ?? 0)}</b>
      </div>
      <div className="usage-line">
        <span>Batas workspace</span>
        <b>{formatLimit(usage?.plan?.limits?.bytesPerWorkspace)}</b>
      </div>
      <div className="usage-line">
        <span>Penegakan kuota</span>
        <b style={{ color: 'var(--green)' }}>{usage?.plan?.enforce ? 'aktif' : 'mati'}</b>
      </div>

      <div style={{ borderTop: '1px solid var(--line)', margin: '8px 0', paddingTop: 10 }}>
        <div className="usage-line">
          <span>Backend sync</span>
          <b>{sync?.backend?.backend ?? '—'}</b>
        </div>
        <div className="usage-line">
          <span>File lokal / remote</span>
          <b>
            {sync?.localFiles ?? 0} / {sync?.remoteFiles ?? 0}
          </b>
        </div>
        <div className="usage-line">
          <span>Menunggu upload</span>
          <b>{sync?.pendingUpload ?? 0}</b>
        </div>
        <div className="usage-line">
          <span>Push terakhir</span>
          <b>{formatTime(sync?.state?.lastPushedAt)}</b>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 6 }}>
        <button className="icon-button" disabled={busy} onClick={() => run(() => api.syncPush(sessionId), 'Sync push selesai')}>
          ↑ Push
        </button>
        <button
          className="icon-button"
          disabled={busy}
          onClick={() => run(() => api.syncPull(sessionId, 'remote-wins'), 'Sync pull selesai')}
        >
          ↓ Pull
        </button>
        <button
          className="icon-button"
          disabled={busy}
          onClick={() => run(() => api.snapshot(sessionId, `snap_${Date.now()}`), 'Snapshot dibuat')}
        >
          ⧉ Snapshot
        </button>
      </div>
    </div>
  );
}
