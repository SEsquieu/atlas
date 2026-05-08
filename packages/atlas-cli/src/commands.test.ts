import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';
import { FileSessionStore, createSessionState } from '@atlas/core';
import { resolveStoreRoot, runAtlasCli } from './commands.js';

test('shrug returns the correct sacred glyph', async () => {
  const result = await runAtlasCli(['shrug']);
  assert.equal(result.exitCode, 0);
  assert.equal(result.stdout, '¯\\_(ツ)_/¯');
});

test('resolveStoreRoot uses explicit --store before environment/default', () => {
  const cwd = resolve('tmp/root');
  assert.equal(resolveStoreRoot(['--store', 'custom-store'], { cwd, env: { ATLAS_STORE: 'env-store' } }), join(cwd, 'custom-store'));
  assert.equal(resolveStoreRoot([], { cwd, env: { ATLAS_STORE: 'env-store' } }), join(cwd, 'env-store'));
  assert.equal(resolveStoreRoot([], { cwd, env: {} }), join(cwd, '.atlas-cache/sessions'));
});

test('config inspect reads atlas config and summarizes configured sessions', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-config-'));
  try {
    await writeFile(
      join(root, 'atlas.config.json'),
      JSON.stringify({
        store: { path: '.atlas-cache/sessions' },
        sessions: {
          garage: {
            name: 'Garage Helper',
            goal: 'Find the right wrench.',
            mode: 'ambient',
            provider: { id: '@atlas/core/testing', adapter: '@atlas/core/testing' },
            devices: [{ id: 'fake-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] }]
          }
        }
      }),
      'utf8'
    );

    const result = await runAtlasCli(['config', 'inspect'], { cwd: root, env: {} });
    assert.equal(result.exitCode, 0);
    const inspected = JSON.parse(result.stdout ?? '{}');
    assert.equal(inspected.storePath, '.atlas-cache/sessions');
    assert.equal(inspected.sessions[0].sessionId, 'garage');
    assert.equal(inspected.sessions[0].devices[0].id, 'fake-camera');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('config inspect returns non-zero when config is missing', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-config-missing-'));
  try {
    const result = await runAtlasCli(['config', 'inspect'], { cwd: root, env: {} });
    assert.equal(result.exitCode, 1);
    assert.match(result.stderr ?? '', /atlas\.config\.json/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('session create writes a file-backed session', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-create-'));
  try {
    const storeRoot = join(root, 'sessions');
    const created = await runAtlasCli(
      [
        'session',
        'create',
        'created-session',
        '--name',
        'Created Session',
        '--goal',
        'Created from CLI.',
        '--provider',
        '@atlas/core/testing',
        '--device',
        'fake-camera:@atlas/core/testing:camera.capture',
        '--store',
        storeRoot
      ],
      { cwd: root, env: {} }
    );

    assert.equal(created.exitCode, 0);
    assert.equal(JSON.parse(created.stdout ?? '{}').sessionId, 'created-session');

    const inspect = await runAtlasCli(['session', 'inspect', 'created-session', '--store', storeRoot], { cwd: root, env: {} });
    const inspected = JSON.parse(inspect.stdout ?? '{}');
    assert.equal(inspected.name, 'Created Session');
    assert.equal(inspected.goal, 'Created from CLI.');
    assert.equal(inspected.devices[0].id, 'fake-camera');
    assert.equal(inspected.events.byType['session.created'], 1);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('session create can materialize a configured session', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-create-configured-'));
  try {
    const storeRoot = join(root, 'sessions');
    const configPath = join(root, 'atlas.config.json');
    await writeFile(
      configPath,
      JSON.stringify({
        sessions: {
          configured: {
            name: 'Configured Session',
            goal: 'Create from atlas.config.json.',
            mode: 'assisted',
            provider: { id: 'configured-provider', adapter: '@atlas/core/testing' },
            devices: [{ id: 'configured-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] }]
          }
        }
      }),
      'utf8'
    );

    const created = await runAtlasCli(['session', 'create', 'configured', '--config', configPath, '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(created.exitCode, 0);
    assert.equal(JSON.parse(created.stdout ?? '{}').fromConfig, true);

    const inspect = await runAtlasCli(['session', 'inspect', 'configured', '--store', storeRoot], { cwd: root, env: {} });
    const inspected = JSON.parse(inspect.stdout ?? '{}');
    assert.equal(inspected.name, 'Configured Session');
    assert.equal(inspected.provider, '@atlas/core/testing');
    assert.equal(inspected.devices[0].id, 'configured-camera');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('session create refuses to overwrite existing sessions', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-create-existing-'));
  try {
    const storeRoot = join(root, 'sessions');
    const first = await runAtlasCli(['session', 'create', 'same-session', '--store', storeRoot], { cwd: root, env: {} });
    const second = await runAtlasCli(['session', 'create', 'same-session', '--store', storeRoot], { cwd: root, env: {} });

    assert.equal(first.exitCode, 0);
    assert.equal(second.exitCode, 1);
    assert.match(second.stderr ?? '', /already exists/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('session ask and heartbeat run through the built-in fake adapter registry', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-runner-'));
  try {
    const storeRoot = join(root, 'sessions');
    const created = await runAtlasCli(
      [
        'session',
        'create',
        'runner-session',
        '--device',
        'fake-camera:@atlas/core/testing:camera.capture',
        '--store',
        storeRoot
      ],
      { cwd: root, env: {} }
    );
    assert.equal(created.exitCode, 0);

    const started = await runAtlasCli(['session', 'start', 'runner-session', '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(started.exitCode, 0);

    const heartbeat = await runAtlasCli(['session', 'heartbeat', 'runner-session', '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(heartbeat.exitCode, 0);
    assert.equal(JSON.parse(heartbeat.stdout ?? '{}').decision.shouldCapture, true);

    const ask = await runAtlasCli(
      ['session', 'ask', 'runner-session', '--text', 'What am I looking at?', '--store', storeRoot],
      { cwd: root, env: {} }
    );
    assert.equal(ask.exitCode, 0);
    assert.match(JSON.parse(ask.stdout ?? '{}').responseText, /observation/);

    const inspect = await runAtlasCli(['session', 'inspect', 'runner-session', '--store', storeRoot], { cwd: root, env: {} });
    const inspected = JSON.parse(inspect.stdout ?? '{}');
    assert.equal(inspected.events.byType['heartbeat.tick'], 1);
    assert.equal(inspected.events.byType['user.utterance'], 1);
    assert.equal(inspected.observations.count, 1);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('session ask and heartbeat can resolve fake adapters selected by config', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-config-runner-'));
  try {
    const storeRoot = join(root, 'sessions');
    const configPath = join(root, 'atlas.config.json');
    await writeFile(
      configPath,
      JSON.stringify({
        sessions: {
          'configured-runner': {
            provider: { id: 'config-provider', adapter: '@atlas/core/testing' },
            devices: [{ id: 'config-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] }],
            analyzers: ['config-analyzer'],
            heartbeat: {
              policy: {
                cadence: { 'active-task': 12_345 },
                minDelayMs: 2_000,
                maxDelayMs: 60_000,
                expectedRefreshLatencyMs: 8_000,
                refreshSafetyMarginMs: 1_000
              }
            }
          }
        }
      }),
      'utf8'
    );

    await runAtlasCli(['session', 'create', 'configured-runner', '--store', storeRoot], { cwd: root, env: {} });
    await runAtlasCli(['session', 'start', 'configured-runner', '--store', storeRoot], { cwd: root, env: {} });

    const heartbeat = await runAtlasCli(
      ['session', 'heartbeat', 'configured-runner', '--store', storeRoot, '--config', configPath],
      { cwd: root, env: {} }
    );
    assert.equal(heartbeat.exitCode, 0);
    const heartbeatJson = JSON.parse(heartbeat.stdout ?? '{}');
    assert.equal(heartbeatJson.decision.cadence.mode, 'active-task');
    assert.equal(heartbeatJson.decision.cadence.nextDelayMs, 12_345);
    assert.equal(heartbeatJson.decision.freshness.expectedRefreshLatencyMs, 8_000);
    assert.equal(heartbeatJson.decision.freshness.safetyMarginMs, 1_000);

    const ask = await runAtlasCli(
      ['session', 'ask', 'configured-runner', '--text', 'Am I in the right place?', '--store', storeRoot, '--config', configPath],
      { cwd: root, env: {} }
    );
    assert.equal(ask.exitCode, 0);

    const inspect = await runAtlasCli(['session', 'inspect', 'configured-runner', '--store', storeRoot], { cwd: root, env: {} });
    const inspected = JSON.parse(inspect.stdout ?? '{}');
    assert.equal(inspected.observations.latest.deviceId, 'config-camera');
    assert.equal(inspected.perception.summary, 'CLI fake visual analyzer summary.');
    assert.equal(inspected.events.checkpoint.materializedThroughLatestEvent, true);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('session ask can resolve command-backed OpenClaw and Android bridge adapters from config', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-command-runner-'));
  try {
    const storeRoot = join(root, 'sessions');
    const bridgeScript = join(root, 'bridge.mjs');
    const providerScript = join(root, 'provider.mjs');
    const configPath = join(root, 'atlas.config.json');

    await writeFile(
      bridgeScript,
      `const options = JSON.parse(process.env.ATLAS_ANDROID_BRIDGE_OPTIONS ?? '{}');\n` +
        `console.log(JSON.stringify({\n` +
        `  mediaRef: 'command://android/current-view.jpg',\n` +
        `  summary: 'Command bridge saw a live-ish scene at maxWidth ' + options.maxWidth + ' quality ' + options.quality + ' delay ' + options.delayMs,\n` +
        `  analysis: { confidence: 0.88, mode: options.analysisMode },\n` +
        `  node: 'command-android-node',\n` +
        `  facing: options.facing,\n` +
        `  requested: { maxWidth: options.maxWidth, quality: options.quality, delayMs: options.delayMs },\n` +
        `  capturedAt: new Date().toISOString(),\n` +
        `  timings: { totalMs: 12, captureMs: 7, stageMs: 2, analysisMs: 3 }\n` +
        `}));\n`,
      'utf8'
    );
    await writeFile(
      providerScript,
      `const turn = JSON.parse(process.env.ATLAS_PROVIDER_TURN ?? '{}');\n` +
        `console.log(JSON.stringify({ turnId: turn.turnId, responseText: 'Command provider received ' + (turn.observations?.length ?? 0) + ' observation(s).' }));\n`,
      'utf8'
    );
    await writeFile(
      configPath,
      JSON.stringify({
        sessions: {
          'command-runner': {
            provider: {
              id: 'command-provider',
              adapter: '@atlas/provider-openclaw/command',
              config: {
                command: process.execPath,
                args: [providerScript],
                timeoutMs: 5000
              }
            },
            devices: [
              {
                id: 'command-android',
                adapter: '@atlas/device-android/bridge-command',
                capabilities: ['camera.capture'],
                config: {
                  command: process.execPath,
                  args: [bridgeScript],
                  analysisMode: 'openclaw',
                  maxWidth: 1024,
                  quality: 0.7,
                  delayMs: 0,
                  timeoutMs: 5000
                }
              }
            ],
            analyzers: []
          }
        }
      }),
      'utf8'
    );

    await runAtlasCli(['session', 'create', 'command-runner', '--store', storeRoot], { cwd: root, env: {} });
    await runAtlasCli(['session', 'start', 'command-runner', '--store', storeRoot], { cwd: root, env: {} });

    const ask = await runAtlasCli(
      ['session', 'ask', 'command-runner', '--text', 'What am I looking at?', '--store', storeRoot, '--config', configPath],
      { cwd: root, env: {} }
    );
    assert.equal(ask.exitCode, 0);
    assert.match(JSON.parse(ask.stdout ?? '{}').responseText, /1 observation/);

    const inspect = await runAtlasCli(['session', 'inspect', 'command-runner', '--store', storeRoot], { cwd: root, env: {} });
    const inspected = JSON.parse(inspect.stdout ?? '{}');
    assert.equal(inspected.observations.latest.deviceId, 'command-android');
    assert.equal(inspected.perception.summary.includes('maxWidth 1024 quality 0.7 delay 0'), true);
    assert.equal(inspected.timing.bridge.totalMs, 12);
    assert.equal(typeof inspected.timing.captureRoundTripMs, 'number');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('session lifecycle commands update status and event log', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-lifecycle-'));
  try {
    const storeRoot = join(root, 'sessions');
    const created = await runAtlasCli(['session', 'create', 'life-session', '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(created.exitCode, 0);

    const started = await runAtlasCli(['session', 'start', 'life-session', '--reason', 'begin test', '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(started.exitCode, 0);
    assert.equal(JSON.parse(started.stdout ?? '{}').status, 'active');

    const paused = await runAtlasCli(['session', 'pause', 'life-session', '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(paused.exitCode, 0);
    assert.equal(JSON.parse(paused.stdout ?? '{}').status, 'paused');

    const resumed = await runAtlasCli(['session', 'resume', 'life-session', '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(resumed.exitCode, 0);
    assert.equal(JSON.parse(resumed.stdout ?? '{}').status, 'active');

    const ended = await runAtlasCli(['session', 'end', 'life-session', '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(ended.exitCode, 0);
    assert.equal(JSON.parse(ended.stdout ?? '{}').status, 'done');

    const inspect = await runAtlasCli(['session', 'inspect', 'life-session', '--store', storeRoot], { cwd: root, env: {} });
    const inspected = JSON.parse(inspect.stdout ?? '{}');
    assert.equal(inspected.status, 'done');
    assert.equal(inspected.events.byType['session.started'], 1);
    assert.equal(inspected.events.byType['session.paused'], 1);
    assert.equal(inspected.events.byType['session.resumed'], 1);
    assert.equal(inspected.events.byType['session.ended'], 1);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('session lifecycle command is idempotent when already in target status', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-lifecycle-idempotent-'));
  try {
    const storeRoot = join(root, 'sessions');
    await runAtlasCli(['session', 'create', 'active-session', '--store', storeRoot], { cwd: root, env: {} });
    const first = await runAtlasCli(['session', 'start', 'active-session', '--store', storeRoot], { cwd: root, env: {} });
    const second = await runAtlasCli(['session', 'start', 'active-session', '--store', storeRoot], { cwd: root, env: {} });

    assert.equal(JSON.parse(first.stdout ?? '{}').changed, true);
    assert.equal(JSON.parse(second.stdout ?? '{}').changed, false);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('sessions list and session inspect read file-backed store', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-'));
  try {
    const storeRoot = join(root, 'sessions');
    const store = new FileSessionStore({ rootDir: storeRoot });
    const session = createSessionState({
      sessionId: 'cli-session',
      name: 'CLI Session',
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
    });
    await store.create(session);

    const list = await runAtlasCli(['sessions', 'list', '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(list.exitCode, 0);
    assert.deepEqual(JSON.parse(list.stdout ?? '{}'), { sessions: ['cli-session'] });

    const inspect = await runAtlasCli(['session', 'inspect', 'cli-session', '--store', storeRoot], { cwd: root, env: {} });
    assert.equal(inspect.exitCode, 0);
    assert.equal(JSON.parse(inspect.stdout ?? '{}').sessionId, 'cli-session');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('unknown session returns non-zero result', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-cli-missing-'));
  try {
    const result = await runAtlasCli(['session', 'inspect', 'missing', '--store', join(root, 'sessions')], { cwd: root, env: {} });
    assert.equal(result.exitCode, 1);
    assert.match(result.stderr ?? '', /Session not found/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
