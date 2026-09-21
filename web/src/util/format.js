export function formatBytes(n) {
  if (n === null || n === undefined || Number.isNaN(n)) return '—';
  if (!Number.isFinite(n)) return '∞';
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = n / 1024;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i += 1;
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[i]}`;
}

/** JSON turns Infinity into null; the plan caps mean "no limit" when null. */
export function formatLimit(value) {
  return value === null || value === undefined ? '∞' : formatBytes(value);
}

export function formatTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const diff = (Date.now() - d.getTime()) / 1000;
  if (diff < 60) return 'baru saja';
  if (diff < 3600) return `${Math.floor(diff / 60)} mnt lalu`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} jam lalu`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)} hr lalu`;
  return d.toLocaleDateString('id-ID', { day: 'numeric', month: 'short' });
}

export function truncate(text, max = 4000) {
  if (!text) return '';
  return text.length > max ? `${text.slice(0, max)}\n… (${text.length - max} karakter dipotong)` : text;
}

/** Flatten a stored message (string or content blocks) into renderable parts. */
export function normaliseMessage(message) {
  const out = { role: message.role, text: '', toolUses: [], toolResults: [] };
  if (typeof message.content === 'string') {
    out.text = message.content;
    return out;
  }
  for (const block of message.content || []) {
    if (block.type === 'text') out.text += block.text;
    else if (block.type === 'tool_use') out.toolUses.push(block);
    else if (block.type === 'tool_result') out.toolResults.push(block);
  }
  return out;
}

export function parseToolResult(result) {
  const raw = typeof result === 'string' ? result : JSON.stringify(result);
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object') return parsed;
  } catch {
    /* fall through */
  }
  return { text: raw };
}
