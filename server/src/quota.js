/**
 * Arealite Quota Policy.
 *
 * The whole point of "workspace unlimited": we always MEASURE storage, we never
 * REFUSE a write because of a limit. The policy object exposes the numbers the
 * UI shows ("1.4 GB / ∞") and an `enforce` flag that is hard-wired to false for
 * every plan that ships with the product.
 *
 * Kept in one file so that if someone ever wants a metered plan they flip a
 * single flag instead of sprinkling `if (tooBig) throw` through the codebase.
 */

export const UNLIMITED = Number.POSITIVE_INFINITY;

export const PLANS = {
  unlimited: {
    id: 'unlimited',
    label: 'Unlimited',
    maxWorkspaces: UNLIMITED,
    maxBytesPerWorkspace: UNLIMITED,
    maxBytesPerFile: UNLIMITED,
    maxMessagesPerSession: UNLIMITED,
    maxSessions: UNLIMITED,
    retentionDays: UNLIMITED,
    enforce: false,
  },
};

export function getPlan(planId = 'unlimited') {
  return PLANS[planId] || PLANS.unlimited;
}

/**
 * Evaluate a prospective write against the plan. Always returns ok=true when the
 * plan is non-enforcing; the warnings are what the UI surfaces.
 */
export function evaluate(plan, usage, request = {}) {
  const warnings = [];
  const softLimitBytes = request.softLimitBytes ?? 1024 * 1024 * 1024; // 1 GB advisory

  if (usage.totalBytes + (request.incomingBytes || 0) > softLimitBytes) {
    warnings.push({
      code: 'SOFT_LIMIT_ADVISORY',
      message: 'Workspace melewati 1 GB. Tetap diizinkan — tidak ada kuota pada workspace ini.',
    });
  }

  let ok = true;
  if (plan.enforce) {
    if (usage.totalBytes + (request.incomingBytes || 0) > plan.maxBytesPerWorkspace) ok = false;
    if (request.incomingBytes > plan.maxBytesPerFile) ok = false;
  }

  return { ok, warnings, plan: plan.id };
}

export function describe(plan) {
  return {
    id: plan.id,
    label: plan.label,
    enforce: plan.enforce,
    limits: {
      workspaces: plan.maxWorkspaces,
      bytesPerWorkspace: plan.maxBytesPerWorkspace,
      bytesPerFile: plan.maxBytesPerFile,
      sessions: plan.maxSessions,
      messagesPerSession: plan.maxMessagesPerSession,
      retentionDays: plan.retentionDays,
    },
  };
}

