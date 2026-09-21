import test from 'node:test';
import assert from 'node:assert/strict';
import { WorkspaceEngine } from '../src/workspace-engine.js';
import { buildTools, TOOL_SCHEMAS } from '../src/tools/index.js';
import { runAgentTurn } from '../src/agent/loop.js';
import { createMockProvider, detectIntent } from '../src/providers/mock.js';
import { tmpDir } from './helpers.js';

function setup() {
  const engine = new WorkspaceEngine({ dataRoot: tmpDir('arealite-agent-') });
  const provider = createMockProvider({ latencyMs: 0 });
  return { engine, provider };
}

test('mock provider: intent detection', () => {
  assert.equal(detectIntent('buatkan fibonacci'), 'fibonacci');
  assert.equal(detectIntent('daftar file di workspace'), 'list');
  assert.equal(detectIntent('berapa pemakaian storage?'), 'usage');
  assert.equal(detectIntent('halo'), 'chat');
});

test('agent loop: writes a file, runs it, and reports the output', async () => {
  const { engine, provider } = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });

  const events = [];
  const result = await runAgentTurn({
    provider,
    model: 'mock-agent',
    messages: [{ role: 'user', content: 'buatkan fibonacci lalu jalankan' }],
    tools,
    emit: (e) => events.push(e),
  });

  const toolEvents = events.filter((e) => e.type === 'tool.end');
  assert.deepEqual(
    toolEvents.map((e) => e.name),
    ['write_file', 'exec'],
  );
  assert.ok(toolEvents.every((e) => e.ok), 'semua tool harus sukses');

  const execEvent = toolEvents.find((e) => e.name === 'exec');
  assert.match(execEvent.result.stdout, /0, 1, 1, 2, 3, 5, 8/);

  assert.equal(result.stopReason, 'end_turn');
  assert.equal(result.turns, 2);

  const file = await engine.readFile(session.id, 'fib.py');
  assert.match(file.text, /def fib/);

  // final assistant text must mention the output
  const finalText = events
    .filter((e) => e.type === 'text_delta')
    .map((e) => e.text)
    .join('');
  assert.match(finalText, /fib\.py/);
});

test('agent loop: tool errors are fed back and the run still terminates', async () => {
  const { engine, provider } = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });
  tools.write_file = async () => ({ ok: false, error: 'disk penuh (simulasi)' });

  const events = [];
  const result = await runAgentTurn({
    provider,
    model: 'mock-agent',
    messages: [{ role: 'user', content: 'buatkan fibonacci' }],
    tools,
    emit: (e) => events.push(e),
  });

  const failed = events.find((e) => e.type === 'tool.end' && e.name === 'write_file');
  assert.equal(failed.ok, false);
  assert.equal(result.stopReason, 'end_turn');
  assert.ok(result.turns <= 12);
});

test('agent loop: respects maxTurns with a tool-hungry provider', async () => {
  const { engine } = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });

  const greedy = {
    id: 'greedy',
    async *stream() {
      yield { type: 'tool_use', id: `t${Math.random()}`, name: 'workspace_usage', input: {} };
      yield { type: 'stop', stopReason: 'tool_use' };
    },
  };

  const result = await runAgentTurn({
    provider: greedy,
    model: 'greedy',
    messages: [{ role: 'user', content: 'loop selamanya' }],
    tools,
    maxTurns: 4,
  });
  assert.equal(result.turns, 4);
  assert.equal(result.stopReason, 'max_turns');
});

test('agent loop: abort signal stops the run', async () => {
  const { engine, provider } = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });
  const controller = new AbortController();
  controller.abort();

  const result = await runAgentTurn({
    provider,
    model: 'mock-agent',
    messages: [{ role: 'user', content: 'buatkan fibonacci' }],
    tools,
    signal: controller.signal,
  });
  assert.equal(result.stopReason, 'aborted');
  assert.equal(result.turns, 0);
});

test('agent loop: the schema list handed to the provider is the tool surface', async () => {
  const { engine } = setup();
  const session = await engine.createSession({});
  const tools = buildTools(engine, { sessionId: session.id, execRoot: engine.dataRoot, net: null });
  let seen = null;
  const spy = {
    id: 'spy',
    async *stream({ tools: t }) {
      seen = t;
      yield { type: 'text_delta', text: 'ok' };
      yield { type: 'stop', stopReason: 'end_turn' };
    },
  };
  await runAgentTurn({ provider: spy, model: 'spy', messages: [{ role: 'user', content: 'hai' }], tools });

  assert.deepEqual(seen, TOOL_SCHEMAS);
  assert.ok(seen.length >= 8, `tool surface terlalu kecil: ${seen.length}`);
  // every advertised schema must actually be callable
  for (const schema of seen) {
    assert.equal(typeof tools[schema.name], 'function', `schema ${schema.name} tidak punya handler`);
  }
});
