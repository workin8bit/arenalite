import { createMockProvider } from './mock.js';
import { createAnthropicProvider } from './anthropic.js';
import { createOpenAiProvider } from './openai.js';
import { createGeminiProvider } from './gemini.js';
import { createOpenRouterProvider } from './openrouter.js';
import { resolveRoute, CATALOGUE } from './models.js';

/**
 * Provider gateway.
 *
 * Arena-style: the client asks for a catalogue model ("arena-agent-1") and this
 * module decides which upstream actually serves it, based on which keys the
 * operator configured. No key configured → the deterministic mock provider, so
 * the product is always demoable.
 *
 * `providers` shape:
 *   { anthropic: { apiKey, baseUrl?, models? }, openai: {...}, gemini: {...}, openrouter: {...} }
 */

const FACTORIES = {
  mock: (cfg) => createMockProvider(cfg || {}),
  anthropic: (cfg) => createAnthropicProvider(cfg),
  openai: (cfg) => createOpenAiProvider(cfg),
  gemini: (cfg) => createGeminiProvider(cfg),
  openrouter: (cfg) => createOpenRouterProvider(cfg),
};

export function createProviderGateway({ providers = {}, defaultProvider = 'mock' } = {}) {
  const cache = new Map();

  function instance(name) {
    if (!cache.has(name)) {
      const factory = FACTORIES[name];
      if (!factory) throw new Error(`Provider tidak dikenal: ${name}`);
      cache.set(name, factory(providers[name] || {}));
    }
    return cache.get(name);
  }

  return {
    catalogue: CATALOGUE,
    configured: Object.keys(providers).filter((k) => providers[k]?.apiKey),
    /** @returns {{ route, provider }} */
    resolve(modelId) {
      const route = resolveRoute({ modelId, providers });
      if (route.provider === 'auto' || !FACTORIES[route.provider]) {
        route.provider = defaultProvider;
      }
      return { route, provider: instance(route.provider) };
    },
    describe() {
      return {
        catalogue: CATALOGUE,
        active: Object.keys(providers).filter((k) => providers[k]?.apiKey),
        fallback: defaultProvider,
      };
    },
  };
}

export { resolveRoute, CATALOGUE };
