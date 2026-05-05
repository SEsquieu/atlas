import assert from 'node:assert/strict';
import { test } from 'node:test';
import { promptLikelyNeedsVisualContext, shouldRefreshContext } from './context-policy.js';

test('visual intent classifier catches physical context questions', () => {
  assert.equal(promptLikelyNeedsVisualContext('What am I looking at?'), true);
  assert.equal(promptLikelyNeedsVisualContext('Am I in the right place?'), true);
  assert.equal(promptLikelyNeedsVisualContext('Can you summarize our plan?'), false);
});

test('refresh policy rejects missing, stale, low-confidence, or unstable context', () => {
  assert.equal(shouldRefreshContext(undefined), true);
  assert.equal(shouldRefreshContext({ available: false }), true);
  assert.equal(shouldRefreshContext({ available: true, ageMs: 60_000, confidence: 0.9, stability: 'stable' }), true);
  assert.equal(shouldRefreshContext({ available: true, ageMs: 1_000, confidence: 0.2, stability: 'stable' }), true);
  assert.equal(shouldRefreshContext({ available: true, ageMs: 1_000, confidence: 0.9, stability: 'transitioning' }), true);
  assert.equal(shouldRefreshContext({ available: true, ageMs: 1_000, confidence: 0.9, stability: 'stable' }), false);
});
