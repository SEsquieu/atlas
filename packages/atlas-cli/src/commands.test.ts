import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';
import { FileSessionStore, createSessionState } from '@atlas/core';
import { resolveStoreRoot, runAtlasCli } from './commands.js';

test('resolveStoreRoot uses explicit --store before environment/default', () => {
  const cwd = resolve('tmp/root');
  assert.equal(resolveStoreRoot(['--store', 'custom-store'], { cwd, env: { ATLAS_STORE: 'env-store' } }), join(cwd, 'custom-store'));
  assert.equal(resolveStoreRoot([], { cwd, env: { ATLAS_STORE: 'env-store' } }), join(cwd, 'env-store'));
  assert.equal(resolveStoreRoot([], { cwd, env: {} }), join(cwd, '.atlas-cache/sessions'));
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
