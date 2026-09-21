'use strict';

import { readSse } from './anthropic.js';

/**
 * OpenAI-compatible adapter. Works for api.openai.com and for OpenRouter
 * (same /chat/completions shape), which is why openrouter.js re-exports it.
 */
export function createOpenAiProvider({ apiKey, baseUrl = 'https://api.openai.com/v1/chat/completions', maxTokens = 4096 }) {
  return {
    id: 'openai',
    async *stream({ messages, system, tools, model, signal }) {
      const body = {
        model,
        stream: true,
        max_tokens: maxTokens,
        messages: system ? [{ role: 'system', content: system }, ...messages] : messages,
        tools: (tools || []).length
          ? (tools || []).map((t) => ({
              type: 'function',
              function: { name: t.name, description: t.description, parameters: t.input_schema },
            }))
          : undefined,
      };

      const res = await fetch(baseUrl, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
        body: JSON.stringify(body),
        signal,
      });

      if (!res.ok) {
        const text = await res.text().catch(() => '');
        throw new Error(`OpenAI ${res.status}: ${text.slice(0, 400)}`);
      }

      const pending = new Map();
      let stopReason = 'end_turn';

      for await (const event of readSse(res, signal)) {
        const choice = event.choices?.[0];
        if (!choice) continue;
        const delta = choice.delta || {};
        if (delta.content) yield { type: 'text_delta', text: delta.content };
        for (const tc of delta.tool_calls || []) {
          const key = tc.index ?? 0;
          const slot = pending.get(key) || { id: tc.id, name: tc.function?.name, json: '' };
          if (tc.id) slot.id = tc.id;
          if (tc.function?.name) slot.name = tc.function.name;
          slot.json += tc.function?.arguments || '';
          pending.set(key, slot);
        }
        if (choice.finish_reason) stopReason = choice.finish_reason === 'tool_calls' ? 'tool_use' : choice.finish_reason;
      }

      for (const call of pending.values()) {
        if (!call.name) continue;
        yield { type: 'tool_use', id: call.id || `call_${Math.random().toString(36).slice(2)}`, name: call.name, input: safeJson(call.json) };
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
