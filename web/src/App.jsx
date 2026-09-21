import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Sidebar from './components/Sidebar.jsx';
import Chat from './components/Chat.jsx';
import SidePanel from './components/SidePanel.jsx';
import SettingsModal from './components/SettingsModal.jsx';
import { api, cancelRun, chatStream, watchSession } from './api/client.js';

export default function App() {
  const [sessions, setSessions] = useState([]);
  const [total, setTotal] = useState(null);
  const [activeId, setActiveId] = useState(null);
  const [detail, setDetail] = useState(null);
  const [messages, setMessages] = useState([]);
  const [streaming, setStreaming] = useState(null);
  const [running, setRunning] = useState(false);
  const [health, setHealth] = useState(null);
  const [models, setModels] = useState(null);
  const [showSettings, setShowSettings] = useState(false);
  const [toast, setToast] = useState(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [panelOpen, setPanelOpen] = useState(true);
  const [draft, setDraft] = useState('');

  const abortRef = useRef(null);
  const toastTimer = useRef(null);

  const notify = useCallback((message, isError = false) => {
    setToast({ message, isError });
    clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 3200);
  }, []);

  const refreshSessions = useCallback(async () => {
    const res = await api.listSessions();
    setSessions(res.sessions);
    setTotal(res.total);
    return res.sessions;
  }, []);

  // initial load: health, models, workspace list
  useEffect(() => {
    (async () => {
      try {
        const [h, m] = await Promise.all([api.health(), api.models()]);
        setHealth(h);
        setModels(m);
      } catch (err) {
        notify(`Server tidak terjangkau: ${err.message}`, true);
      }
      try {
        const list = await refreshSessions();
        if (list.length) setActiveId(list[0].id);
      } catch (err) {
        notify(err.message, true);
      }
    })();
  }, [refreshSessions, notify]);

  // load the selected workspace
  useEffect(() => {
    if (!activeId) {
      setDetail(null);
      setMessages([]);
      return undefined;
    }
    let cancelled = false;
    (async () => {
      try {
        const res = await api.getSession(activeId);
        if (cancelled) return;
        setDetail(res);
        setMessages(res.messages);
      } catch (err) {
        if (!cancelled) notify(err.message, true);
      }
    })();
    const unwatch = watchSession(activeId, (event) => {
      if (event.type === 'file.changed' || event.type === 'exec') setRefreshKey((k) => k + 1);
    });
    return () => {
      cancelled = true;
      unwatch();
    };
  }, [activeId, notify, refreshKey]);

  const createSession = useCallback(async () => {
    try {
      const res = await api.createSession(`Workspace ${sessions.length + 1}`);
      await refreshSessions();
      setActiveId(res.session.id);
      notify('Workspace baru dibuat');
    } catch (err) {
      notify(err.message, true);
    }
  }, [refreshSessions, sessions.length, notify]);

  const deleteSession = useCallback(
    async (session) => {
      if (!window.confirm(`Hapus workspace "${session.title}" dan semua isinya?`)) return;
      try {
        await api.deleteSession(session.id);
        const list = await refreshSessions();
        setActiveId((current) => (current === session.id ? list[0]?.id ?? null : current));
        notify('Workspace dihapus');
      } catch (err) {
        notify(err.message, true);
      }
    },
    [refreshSessions, notify],
  );

  const send = useCallback(async () => {
    const text = draft.trim();
    if (!text || !activeId || running) return;
    setDraft('');
    setRunning(true);
    setMessages((prev) => [...prev, { role: 'user', content: text }]);
    setStreaming({ text: '', tools: [] });

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      await chatStream(activeId, text, detail?.session?.model, (event) => {
        const p = event.payload;
        switch (event.type) {
          case 'text_delta':
            setStreaming((s) => ({ ...s, text: s.text + (p.text || '') }));
            break;
          case 'tool.start':
            setStreaming((s) => ({ ...s, tools: [...s.tools, { call: { id: p.id, name: p.name, input: p.input }, result: null }] }));
            break;
          case 'tool.end':
            setStreaming((s) => ({
              ...s,
              tools: s.tools.map((t) =>
                t.call.id === p.id ? { ...t, result: { ok: p.ok, result: p.result, error: p.error } } : t,
              ),
            }));
            break;
          case 'run.end':
            setStreaming(null);
            break;
          case 'run.error':
            notify(p.error, true);
            setStreaming(null);
            break;
          default:
            break;
        }
      }, controller.signal);
    } catch (err) {
      if (err.name !== 'AbortError') notify(err.message, true);
    } finally {
      setRunning(false);
      setStreaming(null);
      abortRef.current = null;
      // reload persisted history so tool cards survive a refresh
      try {
        const res = await api.getSession(activeId);
        setDetail(res);
        setMessages(res.messages);
      } catch {
        /* ignore */
      }
      refreshSessions();
    }
  }, [draft, activeId, running, detail, notify, refreshSessions]);

  const stop = useCallback(async () => {
    abortRef.current?.abort();
    if (activeId) await cancelRun(activeId).catch(() => {});
    setRunning(false);
  }, [activeId]);

  const patchModel = useCallback(
    async (model) => {
      if (!activeId) return;
      try {
        const res = await api.patchSession(activeId, { model });
        setDetail((d) => ({ ...d, session: res.session }));
        notify(`Model diubah ke ${model}`);
      } catch (err) {
        notify(err.message, true);
      }
    },
    [activeId, notify],
  );

  const onComposerKeyDown = (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      send();
    }
  };

  const usage = detail?.usage;
  const sync = detail?.sync;
  const healthWithQuota = useMemo(
    () => (health ? { ...health, quota: usage?.plan, totalBytes: total?.totalBytes } : null),
    [health, usage, total],
  );

  return (
    <div className={`app ${panelOpen ? '' : 'panel-closed'}`}>
      <Sidebar
        sessions={sessions}
        activeId={activeId}
        total={total}
        onSelect={setActiveId}
        onCreate={createSession}
        onDelete={deleteSession}
        onOpenSettings={() => setShowSettings(true)}
      />

      <main className="main">
        <header className="topbar">
          <h2>{detail?.session?.title || 'Arealite'}</h2>
          {detail?.session && <span className="pill">{detail.session.model}</span>}
          {usage && (
            <span className="pill">
              {usage.files} file · {formatShort(usage.totalBytes)} / ∞
            </span>
          )}
          <span className={`pill ${running ? 'running' : ''}`}>{running ? 'agent bekerja' : 'siap'}</span>
          <button className="icon-button" onClick={() => setPanelOpen((v) => !v)}>
            {panelOpen ? '»' : '«'}
          </button>
        </header>

        <Chat messages={messages} streaming={streaming} running={running} session={detail?.session} />

        <div className="composer">
          <div className="composer-inner">
            <textarea
              rows={1}
              value={draft}
              placeholder={activeId ? 'Minta agent membuat file, menjalankan perintah…' : 'Buat workspace dulu'}
              disabled={!activeId}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={onComposerKeyDown}
            />
            {running ? (
              <button className="send-button stop" onClick={stop}>
                Hentikan
              </button>
            ) : (
              <button className="send-button" onClick={send} disabled={!activeId || !draft.trim()}>
                Kirim
              </button>
            )}
          </div>
          <div className="composer-hint">
            <span>Enter untuk kirim · Shift+Enter untuk baris baru</span>
            <span>{activeId || 'tidak ada workspace'}</span>
          </div>
        </div>
      </main>

      {panelOpen && (
        <SidePanel sessionId={activeId} usage={usage} sync={sync} onNotify={notify} refreshKey={refreshKey} />
      )}

      {showSettings && (
        <SettingsModal
          health={healthWithQuota}
          models={models}
          session={detail?.session}
          onClose={() => setShowSettings(false)}
          onPatchModel={patchModel}
        />
      )}

      {toast && <div className={`toast ${toast.isError ? 'error' : ''}`}>{toast.message}</div>}
    </div>
  );
}

function formatShort(bytes) {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i += 1;
  }
  return `${value.toFixed(value >= 100 || i === 0 ? 0 : 1)} ${units[i]}`;
}
