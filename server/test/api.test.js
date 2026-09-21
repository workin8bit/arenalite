import test from 'node:test';
import assert from 'node:assert/strict';
import { startTestApp } from './helpers.js';

test('api: /api/health reports the mock provider when no keys are set', async (t) => {
  const app = await startTestApp();
  t.after(app.close);
  const res = await app.request('GET', '/api/health');
  assert.equal(res.status, 200);
  assert.equal(res.body.ok, true);
  assert.deepEqual(res.body.providers.active, []);
  assert.equal(res.body.providers.fallback, 'mock');
  assert.equal(res.body.sync.backend, 'local');
});

test('api: create workspace → write file → read file', async (t) => {
  const app = await startTestApp();
  t.after(app.close);

  const created = await app.request('POST', '/api/sessions', { title: 'Lewat API' });
  assert.equal(created.status, 201);
  const id = created.body.session.id;
  assert.equal(created.body.session.plan, 'unlimited');

  const written = await app.request('PUT', `/api/sessions/${id}/files/src/index.js`, { content: 'export const a = 1\n' });
  assert.equal(written.status, 200);
  assert.equal(written.body.created, true);

  const read = await app.request('GET', `/api/sessions/${id}/file/src/index.js`);
  assert.equal(read.status, 200);
  assert.equal(read.body.text, 'export const a = 1\n');

  const list = await app.request('GET', `/api/sessions/${id}/files/src`);
  assert.deepEqual(list.body.entries.map((e) => e.name), ['index.js']);
});

test('api: traversal returns 400, missing session returns 404', async (t) => {
  const app = await startTestApp();
  t.after(app.close);
  const created = await app.request('POST', '/api/sessions', {});
  const id = created.body.session.id;

  const bad = await app.request('PUT', `/api/sessions/${id}/files/..%2f..%2fetc/passwd`, { content: 'x' });
  assert.equal(bad.status, 400);
  assert.equal(bad.body.code, 'EPATHOUTSIDE');

  const missing = await app.request('GET', '/api/sessions/ws_doesnotexist/usage');
  assert.equal(missing.status, 404);
});

test('api: chat streams an agent run that writes and executes a file', async (t) => {
  const app = await startTestApp();
  t.after(app.close);

  const created = await app.request('POST', '/api/sessions', { title: 'Chat uji' });
  const id = created.body.session.id;

  const { status, events } = await app.chat(id, 'buatkan fibonacci lalu jalankan');
  assert.equal(status, 200);

  const types = events.map((e) => e.type);
  assert.ok(types.includes('run.start'), `events: ${types.join(',')}`);
  assert.ok(types.includes('tool.start'));
  assert.ok(types.includes('tool.end'));
  assert.ok(types.includes('run.end'));

  const runStart = events.find((e) => e.type === 'run.start').payload;
  assert.equal(runStart.provider, 'mock');
  assert.equal(runStart.model, 'arena-agent-1');

  const toolsUsed = events.filter((e) => e.type === 'tool.end').map((e) => e.payload.name);
  assert.deepEqual(toolsUsed, ['write_file', 'exec']);

  // the file really landed in the workspace
  const file = await app.request('GET', `/api/sessions/${id}/file/fib.py`);
  assert.equal(file.status, 200);
  assert.match(file.body.text, /def fib/);

  // and the conversation was persisted
  const messages = await app.request('GET', `/api/sessions/${id}/messages`);
  assert.ok(messages.body.messages.length >= 4, `got ${messages.body.messages.length} messages`);
  assert.equal(messages.body.messages[0].role, 'user');
});

test('api: usage endpoint reports infinite caps', async (t) => {
  const app = await startTestApp();
  t.after(app.close);
  const created = await app.request('POST', '/api/sessions', {});
  const id = created.body.session.id;
  await app.request('PUT', `/api/sessions/${id}/files/data.bin`, { content: 'z'.repeat(4096) });

  const usage = await app.request('GET', `/api/sessions/${id}/usage`);
  assert.equal(usage.status, 200);
  assert.ok(usage.body.totalBytes >= 4096);
  assert.equal(usage.body.plan.enforce, false);
  // JSON cannot carry Infinity, so it serialises as null — the UI maps that to ∞
  assert.equal(usage.body.plan.limits.bytesPerWorkspace, null);
});

test('api: exec endpoint runs a shell command in the workspace', async (t) => {
  const app = await startTestApp();
  t.after(app.close);
  const created = await app.request('POST', '/api/sessions', {});
  const id = created.body.session.id;
  const res = await app.request('POST', `/api/sessions/${id}/exec`, { command: 'pwd && ls | head -3' });
  assert.equal(res.status, 200);
  assert.equal(res.body.result.exitCode, 0);
  assert.match(res.body.result.stdout, /README\.md/);
});

test('api: fork, snapshot, sync push/pull round trip over HTTP', async (t) => {
  const app = await startTestApp();
  t.after(app.close);

  const created = await app.request('POST', '/api/sessions', { title: 'Sync uji' });
  const id = created.body.session.id;
  await app.request('PUT', `/api/sessions/${id}/files/keep.txt`, { content: 'isi' });

  const snap = await app.request('POST', `/api/sessions/${id}/snapshot`, { label: 'v1' });
  assert.equal(snap.status, 201);

  const pushed = await app.request('POST', `/api/sessions/${id}/sync/push`);
  assert.equal(pushed.status, 200);
  assert.equal(pushed.body.state.backend, 'local');

  const status = await app.request('GET', `/api/sessions/${id}/sync`);
  assert.equal(status.status, 200);
  assert.equal(status.body.pendingUpload, 0);

  const fork = await app.request('POST', `/api/sessions/${id}/fork`, { title: 'Hasil fork' });
  assert.equal(fork.status, 201);
  const forkedFile = await app.request('GET', `/api/sessions/${fork.body.session.id}/file/keep.txt`);
  assert.equal(forkedFile.body.text, 'isi');
});

test('api: 25 workspaces can be created and listed (no session cap)', async (t) => {
  const app = await startTestApp();
  t.after(app.close);
  for (let i = 0; i < 25; i += 1) {
    // eslint-disable-next-line no-await-in-loop
    await app.request('POST', '/api/sessions', { title: `ws-${i}` });
  }
  const list = await app.request('GET', '/api/sessions');
  assert.equal(list.body.sessions.length, 25);
  assert.equal(list.body.total.plan.enforce, false);
});

test('api: delete removes the workspace', async (t) => {
  const app = await startTestApp();
  t.after(app.close);
  const created = await app.request('POST', '/api/sessions', {});
  const id = created.body.session.id;
  const del = await app.request('DELETE', `/api/sessions/${id}`);
  assert.equal(del.status, 200);
  const after = await app.request('GET', `/api/sessions/${id}/usage`);
  assert.equal(after.status, 404);
});
