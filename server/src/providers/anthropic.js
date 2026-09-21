'use strict';

/**
 * Anthropic Messages API adapter (streaming SSE), normalised to the event shape
 * the agent loop consumes: { type: 'text_delta' | 'tool_use' | 'stop' }.
 */

const ENDPOINT = 'https://api.anthropic.com/v1/messages';

export function createAnthropicProvider({ apiKey, baseUrl = ENDPOINT, maxTokens = 4096 }) {
  return {
    id: 'anthropic',
    async *stream({ messages, system, tools, model, signal }) {
      const body = {
        model,
        max_tokens: maxTokens,
        stream: true,
        system: system || undefined,
        messages,
        tools: (tools || []).map((t) => ({
          name: t.name,
          description: t.description,
          input_schema: t.input_schema,
        })),
      };

      const res = await fetch(baseUrl, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'x-api-key': apiKey,
          'anthropic-version': '2023-06-01',
        },
        body: JSON.stringify(body),
        signal,
      });

      if (!res.ok) {
        const text = await res.text().catch(() => '');
        throw new Error(`Anthropic ${res.status}: ${text.slice(0, 400)}`);
      }

      const toolCalls = new Map();
      let stopReason = 'end_turn';

      for await (const event of readSse(res, signal)) {
        switch (event.type) {
          case 'content_block_start': {
            const block = event.content_block;
            if (block?.type === 'tool_use') {
              toolCalls.set(event.index, { id: block.id, name: block.name, json: '' });
            }
            break;
          }
          case 'content_block_delta': {
            const delta = event.delta;
            if (delta?.type === 'text_delta') yield { type: 'text_delta', text: delta.text };
            if (delta?.type === 'input_json_delta' && toolCalls.has(event.index)) {
              toolCalls.get(event.index).json += delta.partial_json || '';
            }
            break;
          }
          case 'message_delta': {
            if (event.delta?.stop_reason) stopReason = event.delta.stop_reason;
            break;
          }
          default:
            break;
        }
      }

      for (const call of toolCalls.values()) {
        yield { type: 'tool_use', id: call.id, name: call.name, input: safeJson(call.json) };
      }
      yield { type: 'stop', stopReason };
    },
  };
}

function safeJson(text) {
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { _raw: text };
  }
}

/** Minimal SSE reader: enough for the Anthropic/OpenAI streaming formats. */
export async function* readSse(res, signal) {
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      if (signal?.aborted) throw Object.assign(new Error('dibatalkan'), { name: 'AbortError' });
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let sep;
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const raw = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        const dataLines = raw
          .split('\n')
          .filter((l) => l.startsWith('data:'))
          .map((l) => l.slice(5).trim());
        if (!dataLines.length) continue;
        const payload = dataLines.join('\n');
        if (payload === '[DONE]') return;
        try {
          yield JSON.parse(payload);
        } catch {
          /* keep-alive comment or partial frame */
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}
