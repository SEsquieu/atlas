import assert from 'node:assert/strict';
import test from 'node:test';
import { transitionTaskRun } from './task-run.js';

test('task runs have deterministic lifecycle transitions', () => {
  const pending = { taskRunId: 'run-1', status: 'pending' as const };
  const active = transitionTaskRun(pending, 'active', '2026-01-01T00:00:00Z');
  assert.equal(active.startedAt, '2026-01-01T00:00:00Z');
  assert.throws(() => transitionTaskRun(active, 'pending'), /Invalid task-run transition/);
  assert.equal(transitionTaskRun(active, 'completed', '2026-01-01T00:01:00Z').completedAt, '2026-01-01T00:01:00Z');
});
