import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Observation } from '../types.js';
import { assessObservationSignificance } from './significance.js';

function image(partial: Partial<Observation>): Observation {
  return {
    id: partial.id ?? crypto.randomUUID(),
    type: 'image',
    capturedAt: partial.capturedAt ?? new Date().toISOString(),
    deviceId: partial.deviceId ?? 'fake-camera',
    ...partial
  };
}

test('assessObservationSignificance treats first observation as baseline only', () => {
  const current = image({ summary: 'A quiet desk with a laptop and coffee mug.' });
  const result = assessObservationSignificance({ current });

  assert.equal(result.level, 'low');
  assert.equal(result.shouldCallProvider, false);
  assert.equal(result.shouldNotifyUser, false);
});

test('assessObservationSignificance stays silent for unchanged scene summaries', () => {
  const previous = image({ summary: 'A quiet desk with a laptop and coffee mug.', quality: { confidence: 0.9, motion: false } });
  const current = image({ summary: 'Quiet desk with laptop and coffee mug.', quality: { confidence: 0.88, motion: false } });
  const result = assessObservationSignificance({ previous, current });

  assert.equal(result.level, 'none');
  assert.equal(result.shouldCallProvider, false);
});

test('assessObservationSignificance flags meaningful scene changes for provider review', () => {
  const previous = image({ summary: 'A quiet desk with a laptop and coffee mug.', quality: { confidence: 0.9, motion: false } });
  const current = image({ summary: 'A grocery aisle with shelves of cereal and a hanging price sign.', quality: { confidence: 0.9, motion: false } });
  const result = assessObservationSignificance({ previous, current });

  assert.equal(result.level, 'meaningful');
  assert.equal(result.shouldCallProvider, true);
  assert.equal(result.shouldNotifyUser, false);
  assert.ok((result.signals.summaryDelta ?? 0) > 0.35);
});

test('assessObservationSignificance escalates actionable safety cues', () => {
  const previous = image({ summary: 'A kitchen counter with a pot on the stove.', quality: { confidence: 0.9, motion: false } });
  const current = image({ summary: 'Smoke and flame near the stove.', quality: { confidence: 0.9, motion: false } });
  const result = assessObservationSignificance({ previous, current });

  assert.equal(result.level, 'actionable');
  assert.equal(result.shouldCallProvider, true);
  assert.equal(result.shouldNotifyUser, true);
});

test('assessObservationSignificance honors explicit scene-change analysis scores', () => {
  const previous = image({ summary: 'A hallway.', quality: { confidence: 0.9, motion: false } });
  const current = image({
    summary: 'A hallway.',
    quality: { confidence: 0.9, motion: false },
    analyses: [
      {
        id: 'scene-change-1',
        observationId: 'current',
        kind: 'scene-change',
        producedBy: 'test',
        createdAt: new Date().toISOString(),
        data: { sceneDelta: 0.6 }
      }
    ]
  });
  const result = assessObservationSignificance({ previous, current });

  assert.equal(result.level, 'meaningful');
  assert.equal(result.signals.sceneDelta, 0.6);
});
