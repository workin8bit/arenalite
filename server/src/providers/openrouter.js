import { createOpenAiProvider } from './openai.js';

/** OpenRouter speaks the OpenAI chat-completions protocol; only the URL differs. */
export function createOpenRouterProvider({ apiKey, baseUrl = 'https://openrouter.ai/api/v1/chat/completions', maxTokens = 4096 }) {
  return { ...createOpenAiProvider({ apiKey, baseUrl, maxTokens }), id: 'openrouter' };
}
