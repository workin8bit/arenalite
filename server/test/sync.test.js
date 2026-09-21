import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import path from 'path';
import { WorkspaceEngine } from '../src/workspace-engine.js';
import { SessionStore } from '../src/session-store.js';
import { SyncEngine, createRemoteBackend } from '../src/sync/index.js';
import { sigV4Headers } from '../src/sync/sigv4.js';
import { tmpDir } from './helpers.js';

async function setup() {
  const dataRoot = tmpDir('arealite-sync-');
  const engine = new WorkspaceEngine({ dataRoot });
  const store = await SessionStore.open({ file: path.join(dataRoot, 'db.sqlite3') });
  const remoteRoot = tmpDir('arealite-remote-');
  const backend = createRemoteBackend({ backend: 'local', root: remoteRoot });
  return { engine, store, backend, sync: new SyncEngine({ engine, store, backend }), remoteRoot, dataRoot };
}

test('session store: sqlite driver loads and persists messages', async () => {
  const dataRoot = tmpDir('arealite-store-');
  const store = await SessionStore.open({ file: path.join(dataRoot, 'db.sqlite3') });
  assert.equal(store.driver, 'sqlite', `sqlite gagal dimuat: ${store.loadError || 'n/a'}`);

  store.upsertSession({ id: 's1', title: 'Uji', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() });
  store.appendMessage('s1', { role: 'user', content: 'halo' });
  store.appendMessage('s1', { role: 'assistant', content: [{ type: 'text', text: 'hai' }] });

  const messages = store.listMessages('s1');
  assert.equal(messages.length, 2);
  assert.deepEqual(messages[0].content, 'halo');
  assert.deepEqual(messages[1].content, [{ type: 'text', text: 'hai' }]);
  assert.equal(store.countMessages('s1'), 2);
  store.close();
});

test('sync: push uploads only changed files', async () => {
  const { engine, sync, remoteRoot } = await setup();
  const session = await engine.createSession({});
  await engine.writeFile(session.id, 'a.txt', 'satu');
  await engine.writeFile(session.id, 'b.txt', 'dua');

  const first = await sync.push(session.id);
  assert.equal(first.backend, 'local');

  const remoteDir = path.join(remoteRoot, session.id, 'files');
  assert.ok(fs.existsSync(path.join(remoteDir, 'a.txt')));
  assert.equal(fs.readFileSync(path.join(remoteDir, 'a.txt'), 'utf8'), 'satu');

  // unchanged push → nothing new uploaded
  const progress = [];
  await sync.push(session.id, { onProgress: (p) => progress.push(p) });
  assert.equal(progress.filter((p) => p.phase === 'upload').length, 0);

  await engine.writeFile(session.id, 'b.txt', 'dua (ubah)');
  const second = [];
  await sync.push(session.id, { onProgress: (p) => second.push(p) });
  assert.deepEqual(second.map((p) => p.path), ['b.txt']);
});

test('sync: pull restores a workspace onto a fresh engine', async () => {
  const first = await setup();
  const session = await first.engine.createSession({ title: 'Sumber' });
  await first.engine.writeFile(session.id, 'src/main.py', 'print("hai")\n');
  await first.engine.writeFile(session.id, 'data/rows.json', '[1,2,3]');
  await first.sync.push(session.id);

  // second engine points at the same remote
  const secondData = tmpDir('arealite-sync2-');
  const engine2 = new WorkspaceEngine({ dataRoot: secondData });
  const store2 = await SessionStore.open({ file: path.join(secondData, 'db.sqlite3') });
  const backend2 = createRemoteBackend({ backend: 'local', root: first.remoteRoot });
  const sync2 = new SyncEngine({ engine: engine2, store: store2, backend: backend2 });

  const copy = await engine2.createSession({ title: 'Tujuan' });
  // point the copy at the same remote id
  store2.setSyncState(copy.id, { remoteId: session.id });
  const result = await sync2.pull(copy.id);

  assert.ok(result.downloaded >= 2, `expected files to download, got ${result.downloaded}`);
  const file = await engine2.readFile(copy.id, 'src/main.py');
  assert.equal(file.text, 'print("hai")\n');
});

test('sync: remote-wins keeps a local backup on conflict', async () => {
  const { engine, store, sync, remoteRoot } = await setup();
  const session = await engine.createSession({});
  await engine.writeFile(session.id, 'shared.txt', 'versi-1');
  await sync.push(session.id);

  // simulate another device changing the remote copy
  const backend2 = createRemoteBackend({ backend: 'local', root: remoteRoot });
  const engine2 = new WorkspaceEngine({ dataRoot: tmpDir('arealite-sync3-') });
  const store2 = await SessionStore.open({ file: path.join(tmpDir('arealite-sync3db-'), 'db.sqlite3') });
  const copy = await engine2.createSession({});
  store2.setSyncState(copy.id, { remoteId: session.id });
  await new SyncEngine({ engine: engine2, store: store2, backend: backend2 }).pull(copy.id);
  await engine2.writeFile(copy.id, 'shared.txt', 'versi-remote');
  await new SyncEngine({ engine: engine2, store: store2, backend: backend2 }).push(copy.id);

  // original device changed it locally too
  await engine.writeFile(session.id, 'shared.txt', 'versi-lokal');
  const result = await sync.pull(session.id, { strategy: 'remote-wins' });

  assert.equal(result.conflicts.length, 1);
  assert.equal((await engine.readFile(session.id, 'shared.txt')).text, 'versi-remote');
  const backup = await engine.readFile(session.id, result.conflicts[0].backup);
  assert.equal(backup.text, 'versi-lokal');
});

test('sync: status reports pending uploads', async () => {
  const { engine, sync } = await setup();
  const session = await engine.createSession({});
  await engine.writeFile(session.id, 'a.txt', 'a');
  await sync.push(session.id);
  await engine.writeFile(session.id, 'b.txt', 'b');

  const status = await sync.status(session.id);
  assert.equal(status.pendingUpload, 1);
  assert.equal(status.remoteFiles, 2); // README.md + a.txt
});

test('sync: s3 backend signs requests with sigv4', () => {
  const headers = sigV4Headers({
    method: 'PUT',
    url: 'https://s3.us-east-1.amazonaws.com/bucket/key.txt',
    region: 'us-east-1',
    accessKeyId: 'AKIAEXAMPLE',
    secretAccessKey: 'secret',
    body: Buffer.from('hello'),
    now: new Date('2026-01-02T03:04:05Z'),
  });
  assert.match(headers.Authorization, /^AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE\/20260102\/us-east-1\/s3\/aws4_request/);
  assert.equal(headers['x-amz-date'], '20260102T030405Z');
  assert.equal(headers.host, 's3.us-east-1.amazonaws.com');
});

test('sync: large workspace (100 files) pushes without a cap', async () => {
  const { engine, sync, remoteRoot } = await setup();
  const session = await engine.createSession({});
  for (let i = 0; i < 100; i += 1) {
    // eslint-disable-next-line no-await-in-loop
    await engine.writeFile(session.id, `gen/f${i}.txt`, 'y'.repeat(1024));
  }
  const state = await sync.push(session.id);
  assert.equal(state.pushedFiles, 101); // 100 + README.md
  const remoteFiles = fs.readdirSync(path.join(remoteRoot, session.id, 'files', 'gen'));
  assert.equal(remoteFiles.length, 100);
});
