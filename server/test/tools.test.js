import test from 'node:test';
import assert from 'node:assert/strict';
import { WorkspaceEngine } from '../src/workspace-engine.js';
import { buildTools, TOOL_SCHEMAS } from '../src/tools/index.js';
import { tmpDir } from './helpers.js';

function setup() {
  const engine = new WorkspaceEngine({ dataRoot: tmpDir('arealite-tools-') });
  return engine;
}

test('tools: every schema name has an implementation', async () => {
  const engine = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });
  for (const schema of TOOL_SCHEMAS) {
    assert.equal(typeof tools[schema.name], 'function', `tool ${schema.name} belum diimplementasikan`);
  }
});

test('tools: write_file then read_file', async () => {
  const engine = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });

  const written = await tools.write_file({ path: 'notes.md', content: '# halo' });
  assert.equal(written.ok, true);
  const read = await tools.read_file({ path: 'notes.md' });
  assert.equal(read.result.content, '# halo');
});

test('tools: edit_file replaces a unique match and refuses ambiguous ones', async () => {
  const engine = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });

  await tools.write_file({ path: 'app.py', content: 'x = 1\ny = 2\n' });
  const edited = await tools.edit_file({ path: 'app.py', old_string: 'x = 1', new_string: 'x = 42' });
  assert.equal(edited.ok, true);
  assert.equal((await tools.read_file({ path: 'app.py' })).result.content, 'x = 42\ny = 2\n');

  const missing = await tools.edit_file({ path: 'app.py', old_string: 'z = 9', new_string: 'z = 0' });
  assert.equal(missing.ok, false);
  assert.match(missing.error, /tidak ditemukan/);

  await tools.write_file({ path: 'dup.txt', content: 'a\na\n' });
  const ambiguous = await tools.edit_file({ path: 'dup.txt', old_string: 'a', new_string: 'b' });
  assert.equal(ambiguous.ok, false);
  assert.match(ambiguous.error, /muncul 2x/);
});

test('tools: exec runs inside the workspace and reports exit code', async () => {
  const engine = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });

  const result = await tools.exec({ command: 'echo "$ARENALITE_SESSION" && printf ok' });
  assert.equal(result.ok, true);
  assert.equal(result.result.exitCode, 0);
  assert.match(result.result.stdout, new RegExp(session.id));
  assert.match(result.result.stdout, /ok$/);

  const failing = await tools.exec({ command: 'exit 3' });
  assert.equal(failing.result.exitCode, 3);
});

test('tools: exec can run a script the agent just wrote', async () => {
  const engine = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });

  await tools.write_file({ path: 'sum.py', content: 'print(sum(range(11)))\n' });
  const result = await tools.exec({ command: 'python3 sum.py' });
  assert.equal(result.result.exitCode, 0);
  assert.equal(result.result.stdout.trim(), '55');
});

test('tools: exec timeout is honoured', async () => {
  const engine = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });
  const result = await tools.exec({ command: 'sleep 5', timeout_ms: 300 });
  assert.equal(result.result.timedOut, true);
});

test('tools: traversal attempt surfaces as a tool error, not a crash', async () => {
  const engine = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });
  const result = await tools.write_file({ path: '../../pwned.txt', content: 'x' });
  assert.equal(result.ok, false);
  assert.equal(result.code, 'EPATHOUTSIDE');
});

test('tools: fetch_url is blocked unless networking is enabled', async () => {
  const engine = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });
  const result = await tools.fetch_url({ url: 'https://example.com' });
  assert.equal(result.ok, true);
  assert.equal(result.result.blocked, true);
});
