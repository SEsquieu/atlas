#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import http from 'node:http';
import { chmod, mkdir, rm, writeFile } from 'node:fs/promises';
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
  await testNativeSpeakInvokesNodeCommand();
  await testNativeStopInvokesNodeCommand();
  await testNativeTranscribeInvokesNodeCommand();
  await testAutodiscoversWarmWorkerForOpenClawAnalysis();
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

async function testNativeSpeakInvokesNodeCommand() {
  const fakeOpenClawBin = await createFakeOpenClaw('speak');
  const result = await runWrapper('bridge-wrapper.mjs', {}, {
    ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN: fakeOpenClawBin,
    ATLAS_ANDROID_BRIDGE_NODE: 'phone-node-id',
    ATLAS_ANDROID_BRIDGE_SPEAK: JSON.stringify({ text: 'hello native tts', speechId: 'speech-1', rate: 1.1 })
  });

  assert.equal(result.code, 0, result.stderr);
  const parsed = parseJson(result.stdout);
  assert.equal(parsed.command, 'audio.speak');
  assert.deepEqual(parsed.params, { text: 'hello native tts', speechId: 'speech-1', rate: 1.1 });
  const fakeStdout = parseJson(parsed.stdout);
  assert.deepEqual(fakeStdout.argv.slice(0, 7), ['nodes', 'invoke', '--node', 'phone-node-id', '--command', 'audio.speak', '--params']);
  assert.deepEqual(JSON.parse(fakeStdout.argv[7]), { text: 'hello native tts', speechId: 'speech-1', rate: 1.1 });

  pass('native speak invokes audio.speak node command');
}

async function testNativeStopInvokesNodeCommand() {
  const fakeOpenClawBin = await createFakeOpenClaw('stop');
  const result = await runWrapper('bridge-wrapper.mjs', {}, {
    ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN: fakeOpenClawBin,
    ATLAS_ANDROID_BRIDGE_NODE: 'phone-node-id',
    ATLAS_ANDROID_BRIDGE_STOP_SPEAKING: JSON.stringify({ speechId: 'speech-1' })
  });

  assert.equal(result.code, 0, result.stderr);
  const parsed = parseJson(result.stdout);
  assert.equal(parsed.command, 'audio.stop');
  assert.deepEqual(parsed.params, { speechId: 'speech-1' });
  const fakeStdout = parseJson(parsed.stdout);
  assert.deepEqual(fakeStdout.argv.slice(0, 7), ['nodes', 'invoke', '--node', 'phone-node-id', '--command', 'audio.stop', '--params']);
  assert.deepEqual(JSON.parse(fakeStdout.argv[7]), { speechId: 'speech-1' });

  pass('native stop invokes audio.stop node command');
}

async function testNativeTranscribeInvokesNodeCommand() {
  const fakeOpenClawBin = await createFakeOpenClawInvoke('transcribe', {
    ok: true,
    nodeId: 'phone-node-id',
    command: 'audio.transcribe.once',
    payload: {
      ok: true,
      captureId: 'capture-1',
      transcript: 'what am I looking at',
      status: 'ok',
      confidence: 0.88,
      alternatives: ['what am I looking at']
    }
  });
  const result = await runWrapper('bridge-wrapper.mjs', {}, {
    ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN: fakeOpenClawBin,
    ATLAS_ANDROID_BRIDGE_NODE: 'phone-node-id',
    ATLAS_ANDROID_BRIDGE_TRANSCRIBE: JSON.stringify({ language: 'en-US', maxDurationMs: 12345 })
  });

  assert.equal(result.code, 0, result.stderr);
  const parsed = parseJson(result.stdout);
  assert.equal(parsed.command, 'audio.transcribe.once');
  assert.deepEqual(parsed.params, { maxDurationMs: 12345, language: 'en-US' });
  assert.equal(parsed.transcript, 'what am I looking at');
  assert.equal(parsed.status, 'ok');
  assert.equal(parsed.confidence, 0.88);
  const fakeStdout = parseJson(parsed.stdout);
  assert.deepEqual(fakeStdout.argv.slice(0, 7), ['nodes', 'invoke', '--node', 'phone-node-id', '--command', 'audio.transcribe.once', '--params']);
  assert.deepEqual(JSON.parse(fakeStdout.argv[7]), { maxDurationMs: 12345, language: 'en-US' });

  pass('native transcribe invokes audio.transcribe.once node command');
}

async function testAutodiscoversWarmWorkerForOpenClawAnalysis() {
  const fakeOpenClawBin = await createFakeOpenClawCamera('camera-worker-discovery');
  const fakeImagePath = path.join(testRoot, 'fake-camera.jpg');
  const registryPath = path.join(testRoot, 'openclaw-image-worker.json');
  const worker = await startFakeImageWorker();
  try {
    await writeFile(registryPath, `${JSON.stringify({ url: worker.url, pid: process.pid, warm: { status: 'warm' } }, null, 2)}\n`, 'utf8');
    const result = await runWrapper('bridge-wrapper.mjs', { analyze: true, analysisMode: 'openclaw' }, {
      ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN: fakeOpenClawBin,
      ATLAS_OPENCLAW_IMAGE_WORKER_REGISTRY: registryPath,
      ATLAS_ANDROID_BRIDGE_IMAGES_DIR: path.join(testRoot, 'images-worker-discovery'),
      FAKE_OPENCLAW_IMAGE_PATH: fakeImagePath
    });

    assert.equal(result.code, 0, result.stderr);
    const parsed = parseJson(result.stdout);
    assert.equal(parsed.analysisState, 'ok');
    assert.equal(parsed.summary, 'worker summary');
    assert.equal(worker.describeCount(), 1);
  } finally {
    await worker.close();
  }

  pass('bridge wrapper auto-discovers warm image worker');
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

async function createFakeOpenClaw(name) {
  return await createFakeOpenClawInvoke(name, { ok: true });
}

async function createFakeOpenClawInvoke(name, response) {
  const fakeNpmDir = path.join(testRoot, `fake-openclaw-${name}`);
  const fakeModuleDir = path.join(fakeNpmDir, 'node_modules', 'openclaw');
  await mkdir(fakeModuleDir, { recursive: true });
  const modulePath = path.join(fakeModuleDir, 'openclaw.mjs');
  await writeFile(
    modulePath,
    `#!/usr/bin/env node\nprocess.stdout.write(JSON.stringify({ ...${JSON.stringify(response)}, argv: process.argv.slice(2) }) + '\\n');\n`,
    'utf8'
  );
  return await makeFakeOpenClawCommand(fakeNpmDir, modulePath);
}

async function createFakeOpenClawCamera(name) {
  const fakeNpmDir = path.join(testRoot, `fake-openclaw-${name}`);
  const fakeModuleDir = path.join(fakeNpmDir, 'node_modules', 'openclaw');
  await mkdir(fakeModuleDir, { recursive: true });
  const modulePath = path.join(fakeModuleDir, 'openclaw.mjs');
  await writeFile(
    modulePath,
    `#!/usr/bin/env node\nimport fs from 'node:fs';\nconst argv = process.argv.slice(2);\nif (argv[0] === 'nodes' && argv[1] === 'camera' && argv[2] === 'snap') {\n  const imagePath = process.env.FAKE_OPENCLAW_IMAGE_PATH;\n  fs.writeFileSync(imagePath, 'fake image bytes');\n  process.stdout.write('MEDIA:' + imagePath + '\\n');\n  process.exit(0);\n}\nif (argv[0] === 'infer') {\n  process.stderr.write('cold infer should not be used when worker registry is healthy\\n');\n  process.exit(99);\n}\nprocess.stdout.write(JSON.stringify({ ok: true, argv }) + '\\n');\n`,
    'utf8'
  );
  return await makeFakeOpenClawCommand(fakeNpmDir, modulePath);
}

async function makeFakeOpenClawCommand(fakeNpmDir, modulePath) {
  if (process.platform === 'win32') {
    const commandPath = path.join(fakeNpmDir, 'openclaw.cmd');
    await writeFile(commandPath, `@"${process.execPath}" "${modulePath}" %*\r\n`, 'utf8');
    return commandPath;
  }

  await chmod(modulePath, 0o755);
  return modulePath;
}

async function startFakeImageWorker() {
  let describeCount = 0;
  const server = http.createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/health') {
      sendJson(res, 200, { ok: true, warm: { status: 'warm' } });
      return;
    }
    if (req.method === 'POST' && req.url === '/describe') {
      describeCount += 1;
      for await (const _chunk of req) {
        // Drain request body.
      }
      sendJson(res, 200, { ok: true, text: 'worker summary' });
      return;
    }
    sendJson(res, 404, { ok: false, error: 'not found' });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  const port = typeof address === 'object' && address ? address.port : 0;
  return {
    url: `http://127.0.0.1:${port}`,
    describeCount: () => describeCount,
    close: () => new Promise((resolve) => server.close(resolve))
  };
}

function sendJson(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(`${JSON.stringify(body)}\n`);
}

function pass(message) {
  console.log(`✓ ${message}`);
}
