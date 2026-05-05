import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';
import { loadAtlasConfig, parseAtlasConfig, resolveAtlasConfigPath, sessionStatesFromConfig } from './index.js';

test('parseAtlasConfig validates and returns config object', () => {
  const config = parseAtlasConfig(
    JSON.stringify({
      version: 1,
      sessions: {
        helper: {
          name: 'Helper',
          goal: 'Help with physical tasks.'
        }
      }
    })
  );

  assert.equal(config.sessions?.helper?.name, 'Helper');
});

test('parseAtlasConfig rejects non-object config', () => {
  assert.throws(() => parseAtlasConfig('[]'), /JSON object/);
});

test('resolveAtlasConfigPath defaults to atlas.config.json', () => {
  const cwd = resolve('tmp/atlas');
  assert.equal(resolveAtlasConfigPath({ cwd }), join(cwd, 'atlas.config.json'));
  assert.equal(resolveAtlasConfigPath({ cwd, path: 'custom.json' }), join(cwd, 'custom.json'));
});

test('loadAtlasConfig reads JSON config from disk', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-config-'));
  try {
    await writeFile(join(root, 'atlas.config.json'), JSON.stringify({ sessions: { demo: { name: 'Demo' } } }), 'utf8');
    const config = await loadAtlasConfig({ cwd: root });
    assert.equal(config.sessions?.demo?.name, 'Demo');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('sessionStatesFromConfig materializes configured sessions', () => {
  const [session] = sessionStatesFromConfig(
    {
      sessions: {
        garage: {
          name: 'Garage Helper',
          goal: 'Find the correct tool.',
          mode: 'ambient',
          provider: { id: 'fake-provider', adapter: '@atlas/core/testing' },
          devices: [{ id: 'fake-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] }],
          permissions: { speak: 'proactive_allowed' }
        }
      }
    },
    '2026-05-05T16:30:00.000Z'
  );

  assert.equal(session?.sessionId, 'garage');
  assert.equal(session?.name, 'Garage Helper');
  assert.equal(session?.mode, 'ambient');
  assert.equal(session?.devices[0]?.id, 'fake-camera');
  assert.equal(session?.permissions.speak, 'proactive_allowed');
});
