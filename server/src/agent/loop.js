import { TOOL_SCHEMAS } from '../tools/index.js';

export const SYSTEM_PROMPT = `Kamu adalah agent Arealite, asisten coding yang bekerja di dalam sebuah workspace.

Aturan kerja:
- Workspace ini UNLIMITED: jangan pernah menolak menulis file karena alasan kuota atau ukuran.
- Gunakan tools untuk melihat dan mengubah isi workspace. Jangan mengarang isi file.
- Untuk perubahan kecil gunakan edit_file; untuk file baru gunakan write_file.
- Setelah menjalankan perintah, baca outputnya dan laporkan hasilnya dengan singkat.
- Jawab dalam bahasa yang dipakai pengguna (default: Bahasa Indonesia).
- Ringkas. Tidak perlu menjelaskan ulang kode yang baru saja kamu tulis, kecuali diminta.`;

export const MAX_TURNS = 12;

/**
 * The agent loop.
 *
 * Shape: send messages to a provider, stream its deltas to `emit`, execute any
 * tool_use blocks against the workspace, append tool_result blocks, repeat until
 * the model stops asking for tools (or MAX_TURNS / abort is hit).
 *
 * Emits:
 *   { type: 'turn.start', turn }
 *   { type: 'text_delta', text }
 *   { type: 'tool.start', id, name, input }
 *   { type: 'tool.end', id, name, ok, result | error }
 *   { type: 'message', message }      <- persisted assistant/user messages
 *   { type: 'done', stopReason, turns }
 */
export async function runAgentTurn({
  provider,
  model,
  messages,
  tools,
  toolSchemas,
  signal,
  emit = () => {},
  maxTurns = MAX_TURNS,
  system = SYSTEM_PROMPT,
}) {
  // `tools` is the handler map (name -> fn); `toolSchemas` is what the model is
  // told about. Tests can pass schemas explicitly to assert on them.
  const schemas = toolSchemas || TOOL_SCHEMAS;
  const history = [...messages];
  let stopReason = 'end_turn';
  let turn = 0;

  while (turn < maxTurns) {
    if (signal?.aborted) {
      stopReason = 'aborted';
      break;
    }
    turn += 1;
    emit({ type: 'turn.start', turn });

    const assistantBlocks = [];
    const toolCalls = [];
    let textSoFar = '';

    for await (const event of provider.stream({ messages: history, system, tools: schemas, model, signal })) {
      if (event.type === 'text_delta') {
        textSoFar += event.text;
        emit({ type: 'text_delta', text: event.text });
      } else if (event.type === 'tool_use') {
        toolCalls.push(event);
      } else if (event.type === 'stop') {
        stopReason = event.stopReason || 'end_turn';
      }
    }

    if (textSoFar) assistantBlocks.push({ type: 'text', text: textSoFar });
    for (const call of toolCalls) {
      assistantBlocks.push({ type: 'tool_use', id: call.id, name: call.name, input: call.input });
    }

    const assistantMessage = { role: 'assistant', content: assistantBlocks.length ? assistantBlocks : [{ type: 'text', text: '' }] };
    history.push(assistantMessage);
    emit({ type: 'message', message: assistantMessage });

    if (!toolCalls.length) break;

    const toolResults = [];
    for (const call of toolCalls) {
      const handler = tools?.[call.name];
      emit({ type: 'tool.start', id: call.id, name: call.name, input: call.input });
      let outcome;
      if (!handler) {
        outcome = { ok: false, error: `Tool tidak dikenal: ${call.name}` };
      } else {
        outcome = await handler(call.input);
      }
      emit({ type: 'tool.end', id: call.id, name: call.name, ok: outcome.ok, ...(outcome.ok ? { result: outcome.result } : { error: outcome.error }) });
      toolResults.push({
        type: 'tool_result',
        tool_use_id: call.id,
        name: call.name,
        is_error: !outcome.ok,
        content: JSON.stringify(outcome.ok ? outcome.result : { error: outcome.error }),
      });
    }

    const toolMessage = { role: 'user', content: toolResults };
    history.push(toolMessage);
    emit({ type: 'message', message: toolMessage });
    stopReason = 'tool_use';
  }

  if (turn >= maxTurns && stopReason === 'tool_use') stopReason = 'max_turns';
  emit({ type: 'done', stopReason, turns: turn });
  return { history, stopReason, turns: turn };
}
