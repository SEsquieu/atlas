#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const exampleDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(exampleDir, '..', '..');
const testRoot = path.join(repoRoot, '.atlas-runs', 'wrapper-contract-tests');

try {
  await rm(testRoot, { recursive: true, force: true });
  await mkdir(testRoot, { recursive: true });

  console.log('Android/OpenClaw wrapper contract tests (fake/no-camera)');
  console.log(`Store: ${testRoot}`);
  console.log('Phone/camera: not used');
  console.log('');

  await testHeartbeatProviderDefaultsToSummary();
  await testHeartbeatProviderModeOverridesGlobalAgentMode();
  await testUserSummaryModeIncludesVisualHealthCaveat();
  await testCaptureLockTimeoutAvoidsCameraInvocation();

  console.log('All wrapper contract tests passed.');
} catch (error) {
  console.error(error instanceof Error ? error.stack ?? error.message : String(error));
  process.exitCode = 1;
}

async function testHeartbeatProviderDefaultsToSummary() {
  const result = await runWrapper('provider-wrapper.mjs', providerTurn({ trigger: { type: 'heartbeat', reason: 'meaningful scene change' } }), {
    ATLAS_OPENCLAW_BIN: missingCommandPath()
  });

  assert.equal(result.code, 0, result.stderr);
  const parsed = parseJson(result.stdout);
  assert.equal(parsed.turnId, 'turn-1');
  assert.equal(parsed.responseText, 'Ambient review: A grocery aisle with cereal shelves.');

  pass('heartbeat provider defaults to summary fast path');
}

async function testHeartbeatProviderModeOverridesGlobalAgentMode() {
  const result = await runWrapper('provider-wrapper.mjs', providerTurn({ trigger: { type: 'heartbeat', reason: 'meaningful scene change' } }), {
    ATLAS_OPENCLAW_PROVIDER_MODE: 'agent',
    ATLAS_OPENCLAW_HEARTBEAT_PROVIDER_MODE: 'summary',
    ATLAS_OPENCLAW_BIN: missingCommandPath()
  });

  assert.equal(result.code, 0, result.stderr);
  const parsed = parseJson(result.stdout);
  assert.equal(parsed.responseText, 'Ambient review: A grocery aisle with cereal shelves.');

  pass('heartbeat provider summary mode overrides global agent mode');
}

async function testUserSummaryModeIncludesVisualHealthCaveat() {
  const turn = providerTurn({
    trigger: { type: 'user', text: 'What am I looking at?' },
    contextStatus: {
      visual: {
        ageMs: 45_000,
        analysisLatencyMs: 12_300,
        refreshHealth: { status: 'degraded', analysisLatencyMs: 12_300 }
      }
    }
  });
  const result = await runWrapper('provider-wrapper.mjs', turn, {
    ATLAS_OPENCLAW_PROVIDER_MODE: 'summary',
    ATLAS_OPENCLAW_BIN: missingCommandPath()
  });

  assert.equal(result.code, 0, result.stderr);
  const parsed = parseJson(result.stdout);
  assert.match(parsed.responseText, /^Vision is running slowly, this is based on a stable capture from 45s ago, analysis took 12s\. I’m seeing: A grocery aisle with cereal shelves\.$/);

  pass('user summary mode carries degraded visual-health caveat');
}

async function testCaptureLockTimeoutAvoidsCameraInvocation() {
  const lockPath = path.join(testRoot, 'held-camera.lock');
  await mkdir(lockPath, { recursive: true });
  await writeFile(path.join(lockPath, 'owner.json'), JSON.stringify({ pid: process.pid, acquiredAt: new Date().toISOString() }, null, 2), 'utf8');

  const result = await runWrapper('bridge-wrapper.mjs', { analyze: false }, {
    ATLAS_ANDROID_BRIDGE_CAPTURE_LOCK_PATH: lockPath,
    ATLAS_ANDROID_BRIDGE_CAPTURE_LOCK_TIMEOUT_MS: '20',
    ATLAS_ANDROID_BRIDGE_CAPTURE_LOCK_STALE_MS: '300000',
    ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN: missingCommandPath()
  });

  assert.notEqual(result.code, 0, 'bridge wrapper should fail while the fake lock is held');
  assert.match(result.stderr, /Android bridge capture lock timed out after 20ms/);
  assert.doesNotMatch(result.stderr, /definitely-missing-openclaw/);

  pass('capture lock timeout fails before camera/OpenClaw invocation');
}

function providerTurn(overrides = {}) {
  return {
    turnId: 'turn-1',
    session: { sessionId: 'wrapper-contract', provider: { id: 'openclaw' } },
    trigger: { type: 'heartbeat', reason: 'meaningful scene change' },
    contextStatus: {},
    observations: [
      {
        id: 'observation-1',
        type: 'image',
        summary: 'A grocery aisle with cereal shelves.',
        capturedAt: new Date().toISOString()
      }
    ],
    ...overrides
  };
}

async function runWrapper(scriptName, input, env = {}) {
  const scriptPath = path.join(exampleDir, scriptName);
  return await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [scriptPath], {
      cwd: repoRoot,
      env: {
        ...process.env,
        ...env
      },
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true
    });

    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => (stdout += chunk));
    child.stderr.on('data', (chunk) => (stderr += chunk));
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
    child.stdin.end(`${JSON.stringify(input)}\n`);
  });
}

function parseJson(text) {
  return JSON.parse(text.trim());
}

function missingCommandPath() {
  return process.platform === 'win32'
    ? 'C:\\definitely-missing-openclaw-wrapper-contract-test.exe'
    : '/definitely-missing-openclaw-wrapper-contract-test';
}

function pass(message) {
  console.log(`✓ ${message}`);
}
