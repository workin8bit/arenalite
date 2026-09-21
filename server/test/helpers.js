import fs from 'fs';
import os from 'os';
import path from 'path';
import { createApp } from '../src/server.js';

/** Start the real app on an ephemeral port with a throwaway data dir. */
export async function startTestApp({ providers, sync } = {}) {
  const dataRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'arealite-test-'));
  const app = await createApp({ dataRoot, staticRoots: [], providers: providers || {}, sync });
  await new Promise((resolve) => app.server.listen(0, '127.0.0.1', resolve));
  const { port } = app.server.address();
  const base = `http://127.0.0.1:${port}`;

  const request = async (method, pathname, body) => {
    const res = await fetch(base + pathname, {
      method,
      headers: body ? { 'content-type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      json = null;
    }
    return { status: res.status, body: json ?? text, headers: res.headers };
  };

  /** POST /chat and collect the SSE stream into an array of events. */
  const chat = async (sessionId, message, model) => {
    const res = await fetch(`${base}/api/sessions/${sessionId}/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ message, model }),
    });
    const raw = await res.text();
    return { status: res.status, events: parseSse(raw) };
  };

  const close = async () => {
    app.store.close();
    await new Promise((resolve) => app.server.close(resolve));
    fs.rmSync(dataRoot, { recursive: true, force: true });
  };

  return { ...app, base, request, chat, close, dataRoot };
}

export function parseSse(raw) {
  const events = [];
  for (const block of raw.split('\n\n')) {
    const lines = block.split('\n').filter(Boolean);
    if (!lines.length) continue;
    const type = lines.find((l) => l.startsWith('event:'))?.slice(6).trim();
    const data = lines.find((l) => l.startsWith('data:'))?.slice(5).trim();
    if (!type) continue;
    let payload = data;
    try {
      payload = JSON.parse(data);
    } catch {
      /* keep raw */
    }
    events.push({ type, payload });
  }
  return events;
}

export function tmpDir(prefix = 'arealite-') {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}
