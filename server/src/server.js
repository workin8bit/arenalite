import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import { WebSocketServer } from 'ws';

import { WorkspaceEngine } from './workspace-engine.js';
import { SessionStore } from './session-store.js';
import { SyncEngine, createRemoteBackend } from './sync/index.js';
import { createProviderGateway } from './providers/index.js';
import { createApi, statusFor, sendJson } from './api/index.js';
import { createTerminalServer, terminalDriverName } from './terminal.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = Number(process.env.PORT || 3001);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_ROOT = process.env.ARENALITE_DATA || path.join(process.cwd(), 'data');
const WEB_DIST = process.env.ARENALITE_WEB_DIST || path.join(__dirname, '..', '..', 'web', 'dist');
const WEB_PUBLIC = path.join(__dirname, '..', '..', 'web', 'public');

export function createServices({ dataRoot = DATA_ROOT, sync = {}, providers = {} } = {}) {
  const engine = new WorkspaceEngine({ dataRoot });
  const syncBackend = createRemoteBackend({
    backend: sync.backend || 'local',
    root: sync.root || path.join(dataRoot, 'sync', 'remote'),
    ...sync,
  });
  const gateway = createProviderGateway({ providers, defaultProvider: providers.defaultProvider || 'mock' });
  return { engine, syncBackend, gateway };
}

export async function createApp({ dataRoot, staticRoots, sync, providers, allowNet } = {}) {
  const engine = new WorkspaceEngine({ dataRoot });
  const store = await SessionStore.open({ file: path.join(dataRoot, 'arealite.sqlite3') });
  const syncBackend = createRemoteBackend({
    backend: sync?.backend || 'local',
    root: sync?.root || path.join(dataRoot, 'sync', 'remote'),
    ...sync,
  });
  const syncEngine = new SyncEngine({ engine, store, backend: syncBackend });
  const gateway = createProviderGateway({ providers: providers || {}, defaultProvider: providers?.defaultProvider || 'mock' });
  const api = createApi({ engine, store, gateway, syncEngine, dataRoot, staticRoots, allowNet });

  const server = http.createServer((req, res) => api.handle(req, res));
  return { server, api, engine, store, syncEngine, gateway, syncBackend };
}

// ------------------------------------------------------------------ bootstrap

const isMain = process.argv[1] && import.meta.url === `file://${path.resolve(process.argv[1])}`;

if (isMain) {
  const providers = {
    anthropic: { apiKey: process.env.ANTHROPIC_API_KEY, baseUrl: process.env.ANTHROPIC_BASE_URL },
    openai: { apiKey: process.env.OPENAI_API_KEY, baseUrl: process.env.OPENAI_BASE_URL },
    gemini: { apiKey: process.env.GEMINI_API_KEY },
    openrouter: { apiKey: process.env.OPENROUTER_API_KEY },
  };

  const app = await createApp({
    dataRoot: DATA_ROOT,
    staticRoots: [WEB_DIST, WEB_PUBLIC],
    providers,
    sync: {
      backend: process.env.SYNC_BACKEND || 'local',
      root: process.env.SYNC_ROOT || path.join(DATA_ROOT, 'sync', 'remote'),
      endpoint: process.env.S3_ENDPOINT,
      region: process.env.S3_REGION || 'auto',
      bucket: process.env.S3_BUCKET,
      accessKeyId: process.env.S3_ACCESS_KEY_ID,
      secretAccessKey: process.env.S3_SECRET_ACCESS_KEY,
    },
    allowNet: process.env.ARENALITE_ALLOW_NET === '1',
  });

  const wss = new WebSocketServer({ noServer: true });
  app.server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://localhost');
    if (!url.pathname.startsWith('/api/terminal')) {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });
  createTerminalServer({ wss, engine: app.engine });

  app.server.on('clientError', (err, socket) => {
    if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
  });

  app.server.listen(PORT, HOST, () => {
    console.log(`Arealite server → http://${HOST}:${PORT}`);
    console.log(`  data      : ${DATA_ROOT}`);
    console.log(`  store     : ${app.store.driver}`);
    console.log(`  providers : ${app.gateway.describe().active.join(', ') || 'mock (tanpa API key)'}`);
    console.log(`  sync      : ${app.syncBackend.describe().backend}`);
    console.log(`  terminal  : ${terminalDriverName()}`);
  });

  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      app.store.close();
      app.server.close(() => process.exit(0));
    });
  }
}

export { statusFor, sendJson };
