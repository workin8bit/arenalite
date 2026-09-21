import { readSse } from './anthropic.js';

/**
 * Google Gemini generateContent adapter (streamGenerateContent), normalised to
 * the same event shape as the other providers.
 */

const ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta/models';

export function createGeminiProvider({ apiKey, baseUrl = ENDPOINT, maxTokens = 4096 }) {
  return {
    id: 'gemini',
    async *stream({ messages, system, tools, model, signal }) {
      const url = `${baseUrl}/${model}:streamGenerateContent?alt=sse&key=${encodeURIComponent(apiKey)}`;
      const body = {
        systemInstruction: system ? { parts: [{ text: system }] } : undefined,
        contents: messages.map(toGeminiMessage),
        tools: (tools || []).length
          ? [{ functionDeclarations: (tools || []).map((t) => ({ name: t.name, description: t.description, parameters: t.input_schema })) }]
          : undefined,
        generationConfig: { maxOutputTokens: maxTokens },
      };

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
        signal,
      });

      if (!res.ok) {
        const text = await res.text().catch(() => '');
        throw new Error(`Gemini ${res.status}: ${text.slice(0, 400)}`);
      }

      let stopReason = 'end_turn';
      for await (const event of readSse(res, signal)) {
        const candidate = event.candidates?.[0];
        if (!candidate) continue;
        for (const part of candidate.content?.parts || []) {
          if (part.text) yield { type: 'text_delta', text: part.text };
          if (part.functionCall) {
            stopReason = 'tool_use';
            yield {
              type: 'tool_use',
              id: `fc_${Math.random().toString(36).slice(2, 10)}`,
              name: part.functionCall.name,
              input: part.functionCall.args || {},
            };
          }
        }
        if (candidate.finishReason && candidate.finishReason !== 'STOP') stopReason = 'end_turn';
      }
      yield { type: 'stop', stopReason };
    },
  };
}

function toGeminiMessage(message) {
  const role = message.role === 'assistant' ? 'model' : 'user';
  if (typeof message.content === 'string') return { role, parts: [{ text: message.content }] };
  const parts = [];
  for (const block of message.content || []) {
    if (block.type === 'text') parts.push({ text: block.text });
    else if (block.type === 'tool_use') parts.push({ functionCall: { name: block.name, args: block.input } });
    else if (block.type === 'tool_result') {
      parts.push({
        functionResponse: {
          name: block.name || 'tool',
          response: { content: typeof block.content === 'string' ? block.content : JSON.stringify(block.content) },
        },
      });
    }
  }
  return { role, parts };
}
