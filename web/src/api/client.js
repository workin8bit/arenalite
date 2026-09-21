/**
 * Thin client for the Arealite server. Everything is same-origin; the dev
 * server proxies /api to the backend.
 */

async function req(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body ? { 'content-type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.error || `${res.status} ${method} ${path}`);
  return data;
}

export const api = {
  health: () => req('GET', '/api/health'),
  models: () => req('GET', '/api/models'),

  listSessions: () => req('GET', '/api/sessions'),
  createSession: (title, model) => req('POST', '/api/sessions', { title, model }),
  getSession: (id) => req('GET', `/api/sessions/${id}`),
  patchSession: (id, patch) => req('PATCH', `/api/sessions/${id}`, patch),
  deleteSession: (id) => req('DELETE', `/api/sessions/${id}`),
  forkSession: (id, title) => req('POST', `/api/sessions/${id}/fork`, { title }),

  listFiles: (id, path = '.') => req('GET', `/api/sessions/${id}/files/${encodeSegments(path)}`),
  readFile: (id, path) => req('GET', `/api/sessions/${id}/file/${encodeSegments(path)}`),
  writeFile: (id, path, content) => req('PUT', `/api/sessions/${id}/files/${encodeSegments(path)}`, { content }),
  deleteFile: (id, path) => req('DELETE', `/api/sessions/${id}/files/${encodeSegments(path)}`),

  usage: (id) => req('GET', `/api/sessions/${id}/usage`),
  ops: (id, cursor = 0) => req('GET', `/api/sessions/${id}/ops?cursor=${cursor}`),
  snapshot: (id, label) => req('POST', `/api/sessions/${id}/snapshot`, { label }),
  exec: (id, command) => req('POST', `/api/sessions/${id}/exec`, { command }),

  syncStatus: (id) => req('GET', `/api/sessions/${id}/sync`),
  syncPush: (id) => req('POST', `/api/sessions/${id}/sync/push`),
  syncPull: (id, strategy) => req('POST', `/api/sessions/${id}/sync/pull`, { strategy }),
};

export function encodeSegments(p) {
  if (!p || p === '.') return '.';
  return p
    .split('/')
    .filter(Boolean)
    .map((s) => encodeURIComponent(s))
    .join('/');
}

/** POST /chat and stream the SSE events into `onEvent`. */
export async function chatStream(sessionId, message, model, onEvent, signal) {
  const res = await fetch(`/api/sessions/${sessionId}/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ message, model }),
    signal,
  });
  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => '');
    throw new Error(`chat gagal: ${res.status} ${text.slice(0, 200)}`);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let sep;
    while ((sep = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      const type = block.split('\n').find((l) => l.startsWith('event:'))?.slice(6).trim();
      const dataLine = block.split('\n').find((l) => l.startsWith('data:'))?.slice(5).trim();
      if (!type) continue;
      let payload = dataLine;
      try {
        payload = JSON.parse(dataLine);
      } catch {
        /* keep raw */
      }
      onEvent({ type, payload });
    }
  }
}

export function cancelRun(sessionId) {
  return req('POST', `/api/sessions/${sessionId}/cancel`);
}

/** Subscribe to workspace events (file changes, exec output) over SSE. */
export function watchSession(sessionId, onEvent) {
  const source = new EventSource(`/api/sessions/${sessionId}/events`);
  const types = ['file.changed', 'exec', 'message.saved', 'run.start', 'run.end', 'tool.start', 'tool.end', 'text_delta'];
  for (const type of types) {
    source.addEventListener(type, (e) => {
      try {
        onEvent({ type, payload: JSON.parse(e.data) });
      } catch {
        /* ignore malformed frame */
      }
    });
  }
  return () => source.close();
}

export function terminalUrl(sessionId) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${location.host}/api/terminal?sessionId=${encodeURIComponent(sessionId)}`;
}
