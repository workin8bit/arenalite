/**
 * Arena-style managed models: the app ships with a catalogue and the operator
 * decides which upstream provider backs each entry. Users never see an API key
 * field — that is the arena.ai part of the request.
 */

export const CATALOGUE = [
  { id: 'arena-agent-1', label: 'Arena Agent 1', provider: 'auto', context: 200000, default: true },
  { id: 'arena-agent-1-mini', label: 'Arena Agent 1 mini', provider: 'auto', context: 128000 },
  { id: 'arena-fast', label: 'Arena Fast', provider: 'auto', context: 128000 },
];

/**
 * Resolve which concrete provider+model should serve a catalogue id.
 * Priority: explicit config override > provider with a key configured > mock.
 */
export function resolveRoute({ modelId, providers }) {
  const entry = CATALOGUE.find((m) => m.id === modelId) || CATALOGUE[0];
  const configured = Object.entries(providers || {})
    .filter(([, cfg]) => cfg && cfg.apiKey)
    .map(([name]) => name);

  const order = ['anthropic', 'openai', 'gemini', 'openrouter'];
  const chosen = order.find((p) => configured.includes(p)) || 'mock';

  const upstream =
    (providers?.[chosen]?.models && providers[chosen].models[entry.id]) ||
    DEFAULT_UPSTREAM[chosen]?.[entry.id] ||
    DEFAULT_UPSTREAM[chosen]?.default;

  return { modelId: entry.id, label: entry.label, provider: chosen, upstream, context: entry.context };
}

export const DEFAULT_UPSTREAM = {
  anthropic: { default: 'claude-sonnet-4-5', 'arena-agent-1': 'claude-sonnet-4-5', 'arena-fast': 'claude-haiku-4-5' },
  openai: { default: 'gpt-4.1', 'arena-agent-1': 'gpt-4.1', 'arena-fast': 'gpt-4.1-mini' },
  gemini: { default: 'gemini-2.5-pro', 'arena-agent-1': 'gemini-2.5-pro', 'arena-fast': 'gemini-2.5-flash' },
  openrouter: { default: 'anthropic/claude-sonnet-4.5', 'arena-agent-1': 'anthropic/claude-sonnet-4.5' },
  mock: { default: 'mock-agent' },
};

