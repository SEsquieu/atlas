import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveRuntimePolicy } from './runtime-policy.js';

test('policy resolution overlays settings while deny remains monotonic', () => {
  const policy = resolveRuntimePolicy([
    { policyId: 'defaults', revision: 1, inference: { cloudAllowed: false, maxCostMicrosPerRun: 5000 }, tools: { allow: ['capture', 'search'], deny: ['delete'] } },
    { policyId: 'org', revision: 4, inference: { cloudAllowed: true, imagesMayLeaveDevice: false, maxCostMicrosPerRun: 9000 }, tools: { allow: ['capture'], deny: ['search'] } }
  ]);
  assert.equal(policy.inference?.cloudAllowed, false);
  assert.equal(policy.inference?.imagesMayLeaveDevice, false);
  assert.equal(policy.inference?.maxCostMicrosPerRun, 5000);
  assert.deepEqual(policy.tools, { allow: ['capture'], deny: ['delete', 'search'] });
  assert.deepEqual(policy.sourcePolicies, [{ policyId: 'defaults', revision: 1 }, { policyId: 'org', revision: 4 }]);
});
