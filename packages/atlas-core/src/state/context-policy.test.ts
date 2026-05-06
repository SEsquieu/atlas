import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  classifyVisualContextUseCase,
  classifyVisualRefreshLatency,
  evaluateVisualContextFreshness,
  promptLikelyNeedsVisualContext,
  shouldRefreshContext
} from './context-policy.js';

test('visual intent classifier catches physical context questions', () => {
  assert.equal(promptLikelyNeedsVisualContext('What am I looking at?'), true);
  assert.equal(promptLikelyNeedsVisualContext('Am I in the right place?'), true);
  assert.equal(promptLikelyNeedsVisualContext('Can you summarize our plan?'), false);
});

test('visual context use-case classifier separates descriptive, navigation, confirmation, and high-risk asks', () => {
  assert.equal(classifyVisualContextUseCase('What am I looking at?'), 'descriptive');
  assert.equal(classifyVisualContextUseCase('Is this the right part?'), 'confirmation');
  assert.equal(classifyVisualContextUseCase('Am I in the right place?'), 'navigation');
  assert.equal(classifyVisualContextUseCase('Which wire should I cut?'), 'high-risk');
});

test('visual refresh latency classifier returns health bands', () => {
  assert.equal(classifyVisualRefreshLatency(undefined), 'healthy');
  assert.equal(classifyVisualRefreshLatency(4_000), 'healthy');
  assert.equal(classifyVisualRefreshLatency(20_000), 'slow');
  assert.equal(classifyVisualRefreshLatency(80_000), 'degraded');
  assert.equal(classifyVisualRefreshLatency(180_000), 'unavailable');
});

test('refresh policy rejects missing, stale, low-confidence, or unstable context', () => {
  assert.equal(shouldRefreshContext(undefined), true);
  assert.equal(shouldRefreshContext({ available: false }), true);
  assert.equal(shouldRefreshContext({ available: true, ageMs: 60_000, confidence: 0.9, stability: 'stable' }), true);
  assert.equal(shouldRefreshContext({ available: true, ageMs: 1_000, confidence: 0.2, stability: 'stable' }), true);
  assert.equal(shouldRefreshContext({ available: true, ageMs: 1_000, confidence: 0.9, stability: 'transitioning' }), true);
  assert.equal(shouldRefreshContext({ available: true, ageMs: 1_000, confidence: 0.9, stability: 'stable' }), false);
});

test('visual freshness reuses older stable stationary context for descriptive questions', () => {
  const assessment = evaluateVisualContextFreshness(
    {
      available: true,
      ageMs: 60_000,
      confidence: 0.9,
      stability: 'stable',
      motionState: 'stationary'
    },
    { text: 'What am I looking at?' }
  );

  assert.equal(assessment.decision, 'background-refresh');
  assert.equal(assessment.useCase, 'descriptive');
  assert.equal(assessment.maxUsableAgeMs, 120_000);
  assert.ok(assessment.score > 0.4);
});

test('visual freshness refreshes confirmation context much sooner while walking', () => {
  const assessment = evaluateVisualContextFreshness(
    {
      available: true,
      ageMs: 6_000,
      confidence: 0.95,
      stability: 'stable',
      motionState: 'walking'
    },
    { text: 'Is this the right part?' }
  );

  assert.equal(assessment.decision, 'refresh');
  assert.equal(assessment.useCase, 'confirmation');
  assert.equal(assessment.maxUsableAgeMs, 5_000);
});

test('visual freshness degrades to reuse when refresh latency would churn stable context', () => {
  const assessment = evaluateVisualContextFreshness(
    {
      available: true,
      ageMs: 45_000,
      confidence: 0.9,
      stability: 'stable',
      motionState: 'unknown',
      analysisLatencyMs: 80_000
    },
    { text: 'What am I looking at?' }
  );

  assert.equal(assessment.decision, 'degraded-reuse');
  assert.equal(assessment.latencyHealth, 'degraded');
  assert.ok(assessment.reasons.some((reason) => reason.includes('avoid immediate refresh churn')));
});

test('visual freshness still refreshes navigation during degraded refresh latency', () => {
  const assessment = evaluateVisualContextFreshness(
    {
      available: true,
      ageMs: 45_000,
      confidence: 0.9,
      stability: 'stable',
      motionState: 'unknown',
      analysisLatencyMs: 80_000
    },
    { text: 'Am I in the right place?' }
  );

  assert.equal(assessment.decision, 'refresh');
  assert.equal(assessment.latencyHealth, 'degraded');
});

test('visual freshness always refreshes high-risk visual confirmations', () => {
  const assessment = evaluateVisualContextFreshness(
    {
      available: true,
      ageMs: 500,
      confidence: 0.99,
      stability: 'stable',
      motionState: 'stationary'
    },
    { text: 'Which wire should I cut?' }
  );

  assert.equal(assessment.decision, 'refresh');
  assert.equal(assessment.useCase, 'high-risk');
  assert.equal(assessment.maxUsableAgeMs, 0);
});
