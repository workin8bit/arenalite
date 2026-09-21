import test from 'node:test';
import assert from 'node:assert/strict';
import { getPlan, evaluate, describe as describePlan, UNLIMITED } from '../src/quota.js';

test('quota: default plan is unlimited and never enforces', () => {
  const plan = getPlan();
  assert.equal(plan.id, 'unlimited');
  assert.equal(plan.enforce, false);
  assert.equal(plan.maxBytesPerWorkspace, UNLIMITED);
  assert.equal(plan.maxWorkspaces, UNLIMITED);
});

test('quota: a 500 GB write into a 900 GB workspace is still allowed', () => {
  const plan = getPlan('unlimited');
  const GB = 1024 ** 3;
  const decision = evaluate(plan, { totalBytes: 900 * GB }, { incomingBytes: 500 * GB });
  assert.equal(decision.ok, true, 'unlimited plan must never refuse a write');
  assert.equal(decision.plan, 'unlimited');
  assert.ok(decision.warnings.length >= 1, 'should warn past the 1 GB advisory');
  assert.equal(decision.warnings[0].code, 'SOFT_LIMIT_ADVISORY');
});

test('quota: unknown plan id falls back to unlimited', () => {
  assert.equal(getPlan('enterprise-metered-2029').id, 'unlimited');
});

test('quota: describe() reports infinite caps for the UI', () => {
  const described = describePlan(getPlan());
  assert.equal(described.enforce, false);
  assert.equal(described.limits.bytesPerWorkspace, Infinity);
  assert.equal(described.limits.workspaces, Infinity);
});
