import { formatBytes, formatLimit, formatTime } from '../util/format.js';

export default function Sidebar({ sessions, activeId, total, onSelect, onCreate, onDelete, onOpenSettings }) {
  return (
    <aside className="sidebar">
      <div className="brand">
        <div className="brand-mark">A</div>
        <div>
          <h1>Arealite</h1>
          <span>agent workspace · unlimited</span>
        </div>
      </div>

      <button className="new-button" onClick={onCreate}>
        <span>Workspace baru</span>
        <span style={{ opacity: 0.7 }}>+</span>
      </button>

      <div className="session-list">
        {sessions.length === 0 && (
          <div style={{ padding: '16px 10px', color: 'var(--text-faint)', fontSize: 12 }}>
            Belum ada workspace. Buat satu untuk mulai.
          </div>
        )}
        {sessions.map((s) => (
          <div
            key={s.id}
            className={`session-item ${s.id === activeId ? 'active' : ''}`}
            role="button"
            tabIndex={0}
            onClick={() => onSelect(s.id)}
            onKeyDown={(e) => e.key === 'Enter' && onSelect(s.id)}
          >
            <div className="title">{s.title}</div>
            <div className="meta">
              <span>{formatBytes(s.usage?.totalBytes ?? 0)}</span>
              <span>{s.usage?.files ?? 0} file</span>
              <span>{s.messages ?? 0} pesan</span>
              <span style={{ marginLeft: 'auto' }}>{formatTime(s.updatedAt)}</span>
            </div>
            <button
              className="link-button"
              onClick={(e) => {
                e.stopPropagation();
                onDelete(s);
              }}
            >
              hapus
            </button>
          </div>
        ))}
      </div>

      <div className="sidebar-footer">
        <div className="unlimited-badge">∞ tanpa kuota</div>
        {total && (
          <div>
            {total.workspaces} workspace · {total.files} file · {formatBytes(total.totalBytes)} dari{' '}
            {formatLimit(total.plan?.limits?.bytesPerWorkspace)}
          </div>
        )}
        <button className="link-button" onClick={onOpenSettings}>
          Pengaturan model & sinkronisasi →
        </button>
      </div>
    </aside>
  );
}
