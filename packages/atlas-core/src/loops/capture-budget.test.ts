import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { AuditEvent } from '../audit/event-log.js';
import { assessCaptureBudget } from './capture-budget.js';

function captureEvent(atMs: number, latencyMs = 1_000): AuditEvent {
  return {
    id: crypto.randomUUID(),
    type: 'observation.captured',
    at: new Date(atMs).toISOString(),
    data: {
      observation: {
        id: crypto.randomUUID(),
        type: 'image',
        capturedAt: new Date(atMs).toISOString(),
        deviceId: 'fake-camera',
        telemetry: { latencyMs: { total: latencyMs } }
      }
    }
  };
}

test('assessCaptureBudget reports healthy when capture pressure is low', () => {
  const now = Date.now();
  const result = assessCaptureBudget([captureEvent(now - 90_000)], now);

  assert.equal(result.status, 'healthy');
  assert.equal(result.capturesLastMinute, 0);
  assert.equal(result.capturesLastFiveMinutes, 1);
});

test('assessCaptureBudget escalates on capture rate', () => {
  const now = Date.now();
  const warm = assessCaptureBudget([0, 1, 2].map((index) => captureEvent(now - index * 10_000)), now);
  const constrained = assessCaptureBudget([0, 1, 2, 3, 4].map((index) => captureEvent(now - index * 10_000)), now);
  const cooldown = assessCaptureBudget([0, 1, 2, 3, 4, 5, 6, 7].map((index) => captureEvent(now - index * 5_000)), now);

  assert.equal(warm.status, 'warm');
  assert.equal(constrained.status, 'constrained');
  assert.equal(cooldown.status, 'cooldown');
  assert.ok(cooldown.cooldownUntil);
});

test('assessCaptureBudget escalates on recent average latency', () => {
  const now = Date.now();
  const result = assessCaptureBudget([
    captureEvent(now - 10_000, 35_000),
    captureEvent(now - 20_000, 31_000)
  ], now);

  assert.equal(result.status, 'constrained');
  assert.equal(result.averageCaptureLatencyMs, 33_000);
});
