import assert from 'node:assert/strict';
import { test } from 'node:test';
import { analyzeObservationWithPipeline } from './analysis.js';
import { createFakeVisualAnalyzer } from '../testing/fakes.js';
import type { Observation } from '../types.js';

test('analyzeObservationWithPipeline attaches analyzer output without owning provider semantics', async () => {
  const observation: Observation = {
    id: 'obs-1',
    type: 'image',
    capturedAt: '2026-05-05T14:00:00.000Z',
    deviceId: 'fake-camera',
    mediaRef: 'fake://image.jpg',
    quality: { motion: false }
  };

  const analyzed = await analyzeObservationWithPipeline(observation, {
    analyzers: [createFakeVisualAnalyzer({ summary: 'Analyzer-provided visual summary.', confidence: 0.77 })],
    reason: 'test analysis',
    kinds: ['visual-summary']
  });

  assert.equal(analyzed.analyses?.length, 1);
  assert.equal(analyzed.summary, 'Analyzer-provided visual summary.');
  assert.equal(analyzed.quality?.confidence, 0.77);
  assert.equal(analyzed.analyses?.[0]?.producedBy, 'fake-visual-analyzer');
});
