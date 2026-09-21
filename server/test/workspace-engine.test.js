import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import { WorkspaceEngine } from '../src/workspace-engine.js';
import { tmpDir } from './helpers.js';

function newEngine() {
  const dataRoot = tmpDir('arealite-ws-');
  return new WorkspaceEngine({ dataRoot });
}

test('workspace: create, write, read round-trip', async () => {
  const engine = newEngine();
  const session = await engine.createSession({ title: 'Proyek uji' });
  assert.match(session.id, /^ws_\d{8}_[0-9a-f]+$/);

  const written = await engine.writeFile(session.id, 'src/app.js', 'console.log("hai")\n');
  assert.equal(written.created, true);
  assert.equal(written.bytes, Buffer.byteLength('console.log("hai")\n'));

  const file = await engine.readFile(session.id, 'src/app.js');
  assert.equal(file.text, 'console.log("hai")\n');
  assert.match(file.hash, /^[0-9a-f]{64}$/);
});

test('workspace: a 20 MB file writes fine — no quota', async () => {
  const engine = newEngine();
  const session = await engine.createSession({});
  const big = 'x'.repeat(20 * 1024 * 1024);
  const written = await engine.writeFile(session.id, 'big/data.bin', big);
  assert.equal(written.bytes, big.length);
  const usage = await engine.usage(session.id);
  assert.ok(usage.totalBytes >= big.length);
  assert.equal(usage.plan.enforce, false);
  assert.equal(usage.plan.limits.bytesPerWorkspace, Infinity);
});

test('workspace: path traversal is rejected, not clamped', async () => {
  const engine = newEngine();
  const session = await engine.createSession({});
  assert.throws(() => engine.resolve(session.id, '../../etc/passwd'), (err) => err.code === 'EPATHOUTSIDE');
  await assert.rejects(engine.writeFile(session.id, '../escape.txt', 'nope'), /keluar dari workspace/);
});

test('workspace: oplog records every mutation with a monotonic seq', async () => {
  const engine = newEngine();
  const session = await engine.createSession({});
  await engine.writeFile(session.id, 'a.txt', '1');
  await engine.writeFile(session.id, 'a.txt', '22');
  await engine.deletePath(session.id, 'a.txt');

  const { ops, cursor } = await engine.opsSince(session.id, 0);
  // the seed README.md write is logged too; look at the ops for our own file
  const mine = ops.filter((o) => o.path === 'a.txt');
  assert.deepEqual(
    mine.map((o) => o.type),
    ['file.write', 'file.write', 'file.delete'],
  );
  assert.equal(mine[0].detail.created, true);
  assert.equal(mine[1].detail.created, false);
  assert.equal(mine[1].detail.bytes, 2);
  assert.equal(mine[2].detail.existed, true);

  const seqs = ops.map((o) => o.seq);
  assert.deepEqual(seqs, [...seqs].sort((a, b) => a - b), 'seq harus monoton naik');
  assert.equal(ops[0].seq, 1);
  assert.equal(cursor, ops[ops.length - 1].seq);

  const after = await engine.opsSince(session.id, cursor);
  assert.equal(after.ops.length, 0, 'cursor must exclude already-seen ops');
});

test('workspace: usage counts files, dirs and bytes', async () => {
  const engine = newEngine();
  const session = await engine.createSession({});
  await engine.writeFile(session.id, 'a.txt', 'aaaa');
  await engine.writeFile(session.id, 'nested/b.txt', 'bb');
  const usage = await engine.usage(session.id);
  assert.equal(usage.files, 3); // a.txt, nested/b.txt, README.md
  assert.equal(usage.directories, 1);
  assert.equal(usage.largestFileBytes >= 4, true);
});

test('workspace: fork copies files into an independent workspace', async () => {
  const engine = newEngine();
  const session = await engine.createSession({ title: 'Asli' });
  await engine.writeFile(session.id, 'keep.txt', 'isi');

  const copy = await engine.forkSession(session.id);
  assert.notEqual(copy.id, session.id);
  const file = await engine.readFile(copy.id, 'keep.txt');
  assert.equal(file.text, 'isi');

  await engine.writeFile(copy.id, 'keep.txt', 'ubah di copy');
  const original = await engine.readFile(session.id, 'keep.txt');
  assert.equal(original.text, 'isi', 'fork must not alias the source files');
});

test('workspace: 200 workspaces can coexist (no session cap)', async () => {
  const engine = newEngine();
  for (let i = 0; i < 200; i += 1) {
    // eslint-disable-next-line no-await-in-loop
    await engine.createSession({ title: `ws-${i}` });
  }
  const all = await engine.listSessions();
  assert.equal(all.length, 200);
  const total = await engine.globalUsage();
  assert.equal(total.workspaces, 200);
  assert.equal(total.plan.limits.workspaces, Infinity);
});

test('workspace: listFiles returns workspace-relative posix paths', async () => {
  const engine = newEngine();
  const session = await engine.createSession({});
  await engine.writeFile(session.id, 'src/deep/app.js', 'x');
  await engine.writeFile(session.id, 'top.txt', 'y');

  const root = await engine.listFiles(session.id, '.');
  const names = root.map((e) => e.name).sort();
  assert.deepEqual(names, ['README.md', 'src', 'top.txt']);
  assert.ok(root.every((e) => !e.path.startsWith('/')), 'path tidak boleh absolut');
  assert.equal(root.find((e) => e.name === 'src').type, 'dir');

  const nested = await engine.listFiles(session.id, 'src');
  assert.deepEqual(nested.map((e) => e.path), ['src/deep']);
  assert.equal(nested[0].type, 'dir');

  const deep = await engine.listFiles(session.id, 'src/deep');
  assert.deepEqual(deep.map((e) => e.path), ['src/deep/app.js']);
  assert.match(deep[0].modifiedAt, /^\d{4}-\d{2}-\d{2}T/);
  assert.equal(deep[0].size, 1);

  // the returned path must round-trip back into readFile
  const file = await engine.readFile(session.id, deep[0].path);
  assert.equal(file.text, 'x');
});

test('workspace: manifest lists relative paths with hashes for sync diffing', async () => {
  const engine = newEngine();
  const session = await engine.createSession({});
  await engine.writeFile(session.id, 'dir/one.txt', 'satu');
  const manifest = await engine.manifest(session.id);
  const paths = manifest.files.map((f) => f.path);
  assert.ok(paths.includes('dir/one.txt'));
  assert.ok(paths.includes('README.md'));
  assert.ok(manifest.files.every((f) => /^[0-9a-f]{64}$/.test(f.hash)));
});

test('workspace: snapshot copies the tree', async () => {
  const engine = newEngine();
  const session = await engine.createSession({});
  await engine.writeFile(session.id, 'x.txt', 'x');
  const snap = await engine.snapshot(session.id, 'before-big-change');
  assert.equal(snap.label, 'before-big-change');
  assert.ok(snap.files >= 2);
  const dir = `${engine.sessionDir(session.id)}/snapshots/before-big-change`;
  assert.ok(fs.existsSync(`${dir}/x.txt`));
});
