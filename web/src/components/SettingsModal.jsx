import { formatBytes, formatLimit } from '../util/format.js';

/**
 * Settings are read-mostly by design: like arena.ai, the operator holds the
 * upstream provider keys on the server, so the client only shows which provider
 * is currently serving requests and lets the user pick a catalogue model.
 */
export default function SettingsModal({ health, models, session, onClose, onPatchModel }) {
  const providers = health?.providers || {};
  const sync = health?.sync || {};

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Pengaturan</h3>
        <p className="hint">
          Arealite mengelola sendiri koneksi ke model. Pengguna tidak perlu mengisi API key — server yang memegangnya.
        </p>

        <div className="field">
          <label>Model untuk workspace ini</label>
          <select value={session?.model || ''} onChange={(e) => onPatchModel?.(e.target.value)}>
            {(models?.catalogue || []).map((m) => (
              <option key={m.id} value={m.id}>
                {m.label} · {Math.round(m.context / 1000)}k konteks
              </option>
            ))}
          </select>
        </div>

        <div className="field">
          <label>Provider aktif</label>
          <table className="table">
            <tbody>
              <tr>
                <td>upstream</td>
                <td>{providers.active?.length ? providers.active.join(', ') : 'mock (offline, tanpa API key)'}</td>
              </tr>
              <tr>
                <td>fallback</td>
                <td>{providers.fallback || 'mock'}</td>
              </tr>
              <tr>
                <td>store</td>
                <td>{health?.store || '—'}</td>
              </tr>
              <tr>
                <td>sync backend</td>
                <td>
                  {sync.backend || '—'}
                  {sync.root ? ` · ${sync.root}` : ''}
                  {sync.bucket ? ` · ${sync.bucket}` : ''}
                </td>
              </tr>
              <tr>
                <td>data root</td>
                <td>{health?.dataRoot || '—'}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div className="field">
          <label>Kuota</label>
          <table className="table">
            <tbody>
              <tr>
                <td>workspace per akun</td>
                <td>{formatLimit(health?.quota?.limits?.workspaces ?? null)}</td>
              </tr>
              <tr>
                <td>ukuran per workspace</td>
                <td>{formatLimit(health?.quota?.limits?.bytesPerWorkspace ?? null)}</td>
              </tr>
              <tr>
                <td>ukuran per file</td>
                <td>{formatLimit(health?.quota?.limits?.bytesPerFile ?? null)}</td>
              </tr>
              <tr>
                <td>retensi riwayat</td>
                <td>{formatLimit(health?.quota?.limits?.retentionDays ?? null)} hari</td>
              </tr>
            </tbody>
          </table>
        </div>

        <p className="hint">
          Total tersimpan saat ini: <b>{formatBytes(health?.totalBytes ?? 0)}</b>. Tidak ada jalur kode yang menolak
          penulisan karena kuota — flag <code>enforce</code> bernilai <code>false</code> untuk paket unlimited.
        </p>

        <div className="modal-actions">
          <button className="icon-button" onClick={onClose}>
            Tutup
          </button>
        </div>
      </div>
    </div>
  );
}
