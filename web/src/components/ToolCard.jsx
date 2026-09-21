import { useState } from 'react';
import { parseToolResult, truncate } from '../util/format.js';

const ICONS = {
  write_file: '✎',
  edit_file: '✂',
  read_file: '📄',
  list_files: '🗂',
  delete_file: '🗑',
  make_dir: '📁',
  exec: '⌘',
  fetch_url: '🌐',
  workspace_usage: '📊',
};

function summarise(name, input, result) {
  if (!result) return null;
  const parsed = parseToolResult(result);
  switch (name) {
    case 'write_file':
      return `${parsed.path} · ${parsed.bytes} byte${parsed.created ? ' (baru)' : ''}`;
    case 'edit_file':
      return `${parsed.path} · 1 bagian diganti`;
    case 'exec':
      return `exit ${parsed.exitCode ?? '?'}`;
    case 'read_file':
      return `${parsed.path} · ${parsed.size} byte`;
    case 'list_files':
      return `${parsed.entries?.length ?? 0} entri`;
    case 'workspace_usage':
      return `${parsed.files} file · ${parsed.totalBytes} byte`;
    default:
      return null;
  }
}

export default function ToolCard({ call, result, defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen);
  const failed = result && result.ok === false;
  const parsed = result ? parseToolResult(result.ok ? result.result : { error: result.error }) : null;
  const summary = summarise(call.name, call.input, result?.ok ? result : null);

  let detail = '';
  if (parsed) {
    if (call.name === 'exec' && parsed.stdout !== undefined) {
      detail = [parsed.stdout, parsed.stderr ? `[stderr]\n${parsed.stderr}` : ''].filter(Boolean).join('\n') || '(tidak ada output)';
    } else if (parsed.content !== undefined) {
      detail = parsed.content;
    } else if (parsed.entries) {
      detail = parsed.entries.map((e) => `${e.type === 'dir' ? 'd' : '-'} ${e.path}  ${e.size}B`).join('\n');
    } else {
      detail = JSON.stringify(parsed, null, 2);
    }
  }

  return (
    <div className="tool-card">
      <button className="tool-head" onClick={() => setOpen((v) => !v)}>
        <span>{ICONS[call.name] || '🔧'}</span>
        <span className="tool-name">{call.name}</span>
        {summary && <span style={{ color: 'var(--text-faint)' }}>{summary}</span>}
        <span className={`tool-status ${failed ? 'err' : 'ok'}`}>
          {result ? (failed ? 'gagal' : 'selesai') : 'berjalan…'} {open ? '▾' : '▸'}
        </span>
      </button>
      {open && (
        <div className="tool-detail">
          {call.input && Object.keys(call.input).length > 0 && (
            <div style={{ marginBottom: 8, color: 'var(--text-faint)' }}>
              {Object.entries(call.input)
                .map(([k, v]) => `${k}: ${typeof v === 'string' ? truncate(v, 600) : JSON.stringify(v)}`)
                .join('\n')}
            </div>
          )}
          {detail && <div>{truncate(detail, 6000)}</div>}
        </div>
      )}
    </div>
  );
}
