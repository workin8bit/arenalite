import fs from 'fs';
import path from 'path';

import { buildTools, TOOL_SCHEMAS } from '../tools/index.js';
import { runAgentTurn } from '../agent/loop.js';

/**
 * HTTP route factory.
 *
 * Extracted from the bootstrap so tests can drive the real router over an
 * ephemeral port with a temp data directory — no separate "test mode" code path.
 */
export function createApi({ engine, store, gateway, syncEngine, dataRoot, staticRoots = [], allowNet = false }) {
  /** session id -> AbortController for the in-flight agent run */
  const running = new Map();
  /** session id -> Set<response> for SSE subscribers */
  const watchers = new Map();

  function broadcast(sessionId, event) {
    const set = watchers.get(sessionId);
    if (!set) return;
    const frame = `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`;
    for (const res of set) {
      try {
        res.write(frame);
      } catch {
        set.delete(res);
      }
    }
  }

  async function handle(req, res) {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    try {
      await route(req, res, url);
    } catch (err) {
      sendJson(res, statusFor(err), { error: err.message, code: err.code || 'ERROR' });
    }
  }

  async function route(req, res, url) {
    const parts = url.pathname.split('/').filter(Boolean);

    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'content-type, authorization');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PATCH,PUT,DELETE,OPTIONS');
    if (req.method === 'OPTIONS') {
      res.writeHead(204).end();
      return;
    }

    if (url.pathname === '/api/health') {
      sendJson(res, 200, {
        ok: true,
        version: '0.1.0',
        dataRoot,
        store: store.driver,
        providers: gateway.describe(),
        sync: syncEngine.backend.describe(),
      });
      return;
    }

    if (url.pathname === '/api/models') {
      sendJson(res, 200, gateway.describe());
      return;
    }

    // ------------------------------------------------------------- sessions
    if (parts[0] === 'api' && parts[1] === 'sessions' && parts.length === 2) {
      if (req.method === 'GET') {
        const sessions = await engine.listSessions();
        const withUsage = await Promise.all(
          sessions.map(async (s) => ({ ...s, usage: await engine.usage(s.id), messages: store.countMessages(s.id) })),
        );
        sendJson(res, 200, { sessions: withUsage, total: await engine.globalUsage() });
        return;
      }
      if (req.method === 'POST') {
        const body = await readJson(req);
        const meta = await engine.createSession({ title: body.title, model: body.model, planId: body.planId });
        store.upsertSession(meta);
        sendJson(res, 201, { session: meta, usage: await engine.usage(meta.id) });
        return;
      }
    }

    if (parts[0] === 'api' && parts[1] === 'sessions' && parts[2]) {
      const sessionId = parts[2];

      if (parts.length === 3) {
        if (req.method === 'GET') {
          const meta = await engine.readMeta(sessionId);
          sendJson(res, 200, {
            session: meta,
            usage: await engine.usage(sessionId),
            messages: store.listMessages(sessionId),
            sync: await syncEngine.status(sessionId).catch((e) => ({ error: e.message })),
          });
          return;
        }
        if (req.method === 'PATCH') {
          const body = await readJson(req);
          const meta = await engine.writeMeta(sessionId, pick(body, ['title', 'model', 'status']));
          store.touchSession(sessionId, pick(body, ['title', 'model']));
          sendJson(res, 200, { session: meta });
          return;
        }
        if (req.method === 'DELETE') {
          running.get(sessionId)?.abort();
          await engine.deleteSession(sessionId);
          store.deleteSession(sessionId);
          sendJson(res, 200, { deleted: sessionId });
          return;
        }
      }

      switch (parts[3]) {
        case 'fork': {
          const body = await readJson(req).catch(() => ({}));
          const copy = await engine.forkSession(sessionId, { title: body.title });
          store.upsertSession(copy);
          sendJson(res, 201, { session: copy, usage: await engine.usage(copy.id) });
          return;
        }
        case 'messages': {
          if (req.method === 'GET') {
            sendJson(res, 200, { messages: store.listMessages(sessionId) });
            return;
          }
          if (req.method === 'POST') {
            const body = await readJson(req);
            const saved = store.appendMessage(sessionId, { role: body.role || 'user', content: body.content });
            broadcast(sessionId, { type: 'message.saved', sessionId, message: saved });
            sendJson(res, 201, { message: saved });
            return;
          }
          break;
        }
        case 'files': {
          const relPath = decodeURIComponent(parts.slice(4).join('/') || '.');
          if (req.method === 'GET') {
            sendJson(res, 200, { entries: await engine.listFiles(sessionId, relPath) });
            return;
          }
          if (req.method === 'PUT' || req.method === 'POST') {
            const body = await readJson(req);
            const content = typeof body.content === 'string' ? body.content : Buffer.from(body.base64 || '', 'base64');
            const result = await engine.writeFile(sessionId, relPath, content, { source: 'api' });
            broadcast(sessionId, { type: 'file.changed', sessionId, ...result });
            sendJson(res, 200, result);
            return;
          }
          if (req.method === 'DELETE') {
            const result = await engine.deletePath(sessionId, relPath);
            broadcast(sessionId, { type: 'file.changed', sessionId, ...result });
            sendJson(res, 200, result);
            return;
          }
          break;
        }
        case 'file': {
          const relPath = decodeURIComponent(parts.slice(4).join('/'));
          if (req.method === 'GET') {
            const file = await engine.readFile(sessionId, relPath);
            if (url.searchParams.get('raw') === '1') {
              res.writeHead(200, { 'content-type': 'application/octet-stream' });
              res.end(Buffer.from(file.base64, 'base64'));
              return;
            }
            sendJson(res, 200, file);
            return;
          }
          break;
        }
        case 'move': {
          const body = await readJson(req);
          sendJson(res, 200, await engine.movePath(sessionId, body.from, body.to));
          return;
        }
        case 'usage': {
          sendJson(res, 200, await engine.usage(sessionId));
          return;
        }
        case 'ops': {
          const cursor = Number(url.searchParams.get('cursor') || 0);
          sendJson(res, 200, await engine.opsSince(sessionId, cursor));
          return;
        }
        case 'snapshot': {
          const body = await readJson(req).catch(() => ({}));
          sendJson(res, 201, await engine.snapshot(sessionId, body.label));
          return;
        }
        case 'exec': {
          const body = await readJson(req);
          const tools = buildTools(engine, { sessionId, execRoot: dataRoot, net: null });
          const result = await tools.exec({ command: body.command, cwd: body.cwd || '.', timeout_ms: body.timeout_ms });
          broadcast(sessionId, { type: 'exec', sessionId, ...(result.result || result) });
          sendJson(res, 200, result);
          return;
        }
        case 'sync': {
          const action = parts[4];
          if (req.method === 'GET') {
            sendJson(res, 200, await syncEngine.status(sessionId));
            return;
          }
          if (action === 'push') {
            sendJson(res, 200, { state: await syncEngine.push(sessionId) });
            return;
          }
          if (action === 'pull') {
            const body = await readJson(req).catch(() => ({}));
            sendJson(res, 200, await syncEngine.pull(sessionId, { strategy: body.strategy }));
            return;
          }
          break;
        }
        case 'events': {
          res.writeHead(200, {
            'content-type': 'text/event-stream',
            'cache-control': 'no-cache, no-transform',
            connection: 'keep-alive',
            'x-accel-buffering': 'no',
          });
          res.write(`event: hello\ndata: ${JSON.stringify({ sessionId, at: new Date().toISOString() })}\n\n`);
          if (!watchers.has(sessionId)) watchers.set(sessionId, new Set());
          watchers.get(sessionId).add(res);
          const ping = setInterval(() => res.write(': ping\n\n'), 25000);
          req.on('close', () => {
            clearInterval(ping);
            watchers.get(sessionId)?.delete(res);
          });
          return;
        }
        case 'chat': {
          if (req.method === 'POST') {
            await handleChat(req, res, sessionId);
            return;
          }
          break;
        }
        case 'cancel': {
          const controller = running.get(sessionId);
          if (!controller) {
            sendJson(res, 409, { error: 'Tidak ada proses berjalan' });
            return;
          }
          controller.abort();
          sendJson(res, 200, { cancelled: sessionId });
          return;
        }
        default:
          break;
      }
    }

    const served = await serveStatic(res, url.pathname, staticRoots);
    if (!served) sendJson(res, 404, { error: `Tidak ditemukan: ${url.pathname}` });
  }

  async function handleChat(req, res, sessionId) {
    const body = await readJson(req);
    const meta = await engine.readMeta(sessionId);
    const modelId = body.model || meta.model || 'arena-agent-1';
    const userText = typeof body.message === 'string' ? body.message : '';
    if (!userText.trim()) {
      sendJson(res, 400, { error: 'message tidak boleh kosong' });
      return;
    }

    const userMessage = { role: 'user', content: userText };
    store.appendMessage(sessionId, userMessage);
    await engine.writeMeta(sessionId, { status: 'running' });
    broadcast(sessionId, { type: 'message.saved', sessionId, message: userMessage });

    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    });
    const sse = (event) => {
      res.write(`event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`);
      broadcast(sessionId, event);
    };

    const controller = new AbortController();
    running.set(sessionId, controller);
    req.on('close', () => {
      if (running.get(sessionId) === controller) running.delete(sessionId);
    });

    const { route, provider } = gateway.resolve(modelId);
    const tools = buildTools(engine, {
      sessionId,
      execRoot: dataRoot,
      net: allowNet ? { fetch: simpleFetch } : null,
      log: (entry) => sse({ ...entry, sessionId }),
    });

    sse({ type: 'run.start', sessionId, model: route.modelId, provider: route.provider, upstream: route.upstream });

    try {
      const messages = store.providerMessages(sessionId);
      const result = await runAgentTurn({
        provider,
        model: route.upstream,
        messages,
        tools,
        toolSchemas: TOOL_SCHEMAS,
        signal: controller.signal,
        emit: (event) => {
          if (event.type === 'message') {
            store.appendMessage(sessionId, event.message);
            sse({ type: 'message.saved', sessionId, message: event.message });
            return;
          }
          sse({ ...event, sessionId });
        },
      });
      await engine.writeMeta(sessionId, { status: 'idle', model: modelId });
      sse({ type: 'run.end', sessionId, stopReason: result.stopReason, turns: result.turns, usage: await engine.usage(sessionId) });
    } catch (err) {
      await engine.writeMeta(sessionId, { status: 'idle' });
      sse({ type: 'run.error', sessionId, error: err.message, name: err.name });
    } finally {
      running.delete(sessionId);
      res.end();
    }
  }

  return { handle, broadcast, running, watchers };
}

// --------------------------------------------------------------------- shared

export function pick(obj, keys) {
  const out = {};
  for (const k of keys) if (obj && obj[k] !== undefined) out[k] = obj[k];
  return out;
}

export function statusFor(err) {
  switch (err.code) {
    case 'ENOENT':
    case 'ENOSESSION':
      return 404;
    case 'EPATHOUTSIDE':
    case 'EROOT':
    case 'EISDIR':
    case 'EBADJSON':
      return 400;
    case 'EQUOTA':
      return 413;
    default:
      return 500;
  }
}

export async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch {
    const err = new Error('Body bukan JSON valid');
    err.code = 'EBADJSON';
    throw err;
  }
}

export function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
  });
  res.end(body);
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.map': 'application/json',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

export async function serveStatic(res, pathname, roots) {
  const rel = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, '');
  for (const root of roots || []) {
    if (!fs.existsSync(root)) continue;
    const base = path.resolve(root);
    const candidate = path.resolve(base, rel);
    if (!candidate.startsWith(base)) continue;
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
      const buf = fs.readFileSync(candidate);
      res.writeHead(200, { 'content-type': MIME[path.extname(candidate)] || 'application/octet-stream' });
      res.end(buf);
      return true;
    }
    const index = path.join(base, 'index.html');
    if (fs.existsSync(index) && !path.extname(rel)) {
      res.writeHead(200, { 'content-type': MIME['.html'] });
      res.end(fs.readFileSync(index));
      return true;
    }
  }
  return false;
}

async function simpleFetch(url, method = 'GET') {
  const res = await fetch(url, { method, redirect: 'follow' });
  const text = await res.text();
  return { status: res.status, url: res.url, body: text.slice(0, 8000) };
}
