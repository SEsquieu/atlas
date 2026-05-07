#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const ambientLoopPath = path.join(repoRoot, 'examples', 'ambient-loop.mjs');
const imageWorkerPath = path.join(repoRoot, 'examples', 'android-openclaw-basic', 'openclaw-image-worker.mjs');
const configPath = path.join(repoRoot, 'examples', 'android-openclaw-basic', 'atlas-live.config.example.json');
const args = parseArgs(process.argv.slice(2));
const action = args._[0] ?? 'status';
const session = args.session ?? process.env.ATLAS_AMBIENT_SESSION_ID ?? 'live-android-openclaw';
const store = args.store ?? process.env.ATLAS_AMBIENT_STORE ?? path.join(repoRoot, '.atlas-runs', 'latest-ambient-android');
const runDir = path.join(store, session);
const controlPath = path.join(runDir, 'loop-control.json');
const stdoutPath = path.join(runDir, 'loop.stdout.log');
const stderrPath = path.join(runDir, 'loop.stderr.log');
const summaryPath = path.join(runDir, 'ambient-loop.md');
const jsonlPath = path.join(runDir, 'ambient-loop.jsonl');

try {
  if (action === 'start') await startLoop();
  else if (action === 'stop') await stopLoop();
  else if (action === 'status') await printStatus();
  else if (action === 'summary') await printSummary();
  else if (action === 'ask') await askLoop();
  else if (action === 'fresh-start') await freshStartLoop();
  else if (action === '--help' || action === '-h' || action === 'help') console.log(helpText());
  else throw new Error(`Unknown action: ${action}\n\n${helpText()}`);
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}

async function startLoop({ fresh = false } = {}) {
  await mkdir(runDir, { recursive: true });
  const existing = await readControl();
  if (existing?.pid && isProcessAlive(existing.pid)) {
    console.log(`Atlas loop already running: pid=${existing.pid}`);
    console.log(`Summary: ${summaryPath}`);
    return;
  }

  if (fresh) {
    await rm(runDir, { recursive: true, force: true });
    await mkdir(runDir, { recursive: true });
  }

  const ticks = String(args.ticks ?? 9999);
  const maxSleepMs = String(args.maxSleepMs ?? 30_000);
  const loopArgs = [
    ambientLoopPath,
    '--config', configPath,
    '--session', session,
    '--store', store,
    '--ticks', ticks,
    '--wait',
    '--max-sleep-ms', maxSleepMs,
    '--image-worker', args.imageWorker ?? 'auto'
  ];

  const stdout = await openAppend(stdoutPath);
  const stderr = await openAppend(stderrPath);
  const child = spawn(process.execPath, loopArgs, {
    cwd: repoRoot,
    env: sanitizeEnv(process.env),
    stdio: ['ignore', stdout, stderr],
    detached: true,
    windowsHide: true
  });
  child.unref();
  await writeControl({
    pid: child.pid,
    startedAt: new Date().toISOString(),
    session,
    store,
    runDir,
    summaryPath,
    jsonlPath,
    stdoutPath,
    stderrPath,
    ticks: Number(ticks),
    maxSleepMs: Number(maxSleepMs),
    fresh
  });
  console.log(`Started Atlas loop: pid=${child.pid}`);
  console.log(`Session: ${session}`);
  console.log(`Store: ${store}`);
  console.log(`Summary: ${summaryPath}`);
  console.log(`Logs: ${stdoutPath}`);
}

async function freshStartLoop() {
  const existing = await readControl();
  if (existing?.pid && isProcessAlive(existing.pid)) throw new Error('Atlas loop is already running. Stop it before fresh-start.');
  await startLoop({ fresh: true });
}

async function stopLoop() {
  const control = await readControl();
  if (!control?.pid) {
    console.log('Atlas loop is not running (no control file).');
    return;
  }
  if (!isProcessAlive(control.pid)) {
    console.log(`Atlas loop is not running (stale pid=${control.pid}).`);
    await writeControl({ ...control, stoppedAt: new Date().toISOString(), stale: true });
    return;
  }

  await stopProcess(control.pid);
  await writeControl({ ...control, stoppedAt: new Date().toISOString() });
  console.log(`Stopped Atlas loop: pid=${control.pid}`);
  console.log(`Summary: ${control.summaryPath ?? summaryPath}`);
}

async function printStatus() {
  const control = await readControl();
  const running = Boolean(control?.pid && isProcessAlive(control.pid));
  console.log(`Atlas loop status: ${running ? 'running' : 'stopped'}`);
  if (control?.pid) console.log(`PID: ${control.pid}`);
  console.log(`Session: ${control?.session ?? session}`);
  console.log(`Store: ${control?.store ?? store}`);
  console.log(`Summary: ${control?.summaryPath ?? summaryPath}`);
  if (control?.startedAt) console.log(`Started: ${control.startedAt}`);
  if (control?.stoppedAt) console.log(`Stopped: ${control.stoppedAt}`);
  const tickCount = await countJsonlLines(control?.jsonlPath ?? jsonlPath);
  console.log(`Recorded ticks: ${tickCount}`);
}

async function askLoop() {
  const text = (args.text ?? args._.slice(1).join(' ')).trim();
  if (!text) throw new Error('Usage: npm run loop:android -- ask "What am I looking at?"');
  await ensureConfiguredSession();
  process.env.ATLAS_OPENCLAW_PROVIDER_MODE = args.providerMode ?? 'summary';
  let imageWorker;
  try {
    imageWorker = await maybeStartImageWorker();
    if (imageWorker?.url) console.log(`OpenClaw image worker: ${imageWorker.url}`);
    const result = await atlasJson(['session', 'ask', session, '--text', text, '--config', configPath, '--store', store]);
    const inspection = await atlasJson(['session', 'inspect', session, '--store', store]);
    console.log(result.responseText ?? '(no response text)');
    console.log('');
    console.log(`- refreshed during ask: ${result.refreshedObservationId ?? 'no'}`);
    const bridge = latestBridgeTiming(inspection);
    console.log(`- user turn: ${formatMs(inspection.timing?.userTurnMs)}`);
    console.log(`- capture round trip: ${formatMs(inspection.timing?.captureRoundTripMs)}`);
    if (bridge) {
      console.log(`- bridge total: ${formatMs(bridge.totalMs)}`);
      console.log(`  - camera/helper capture: ${formatMs(bridge.captureMs)}`);
      console.log(`  - file stage: ${formatMs(bridge.stageMs)}`);
      console.log(`  - image analysis: ${formatMs(bridge.analysisMs)}`);
    }
    console.log(`- provider round trip: ${formatMs(inspection.timing?.providerRoundTripMs)}`);
    console.log(`- latest observation: ${inspection.observations?.latest?.id ?? 'none'}`);
  } finally {
    imageWorker?.stop();
  }
}

async function maybeStartImageWorker() {
  if (args.imageWorker === 'false') return null;
  const existingUrl = currentImageWorkerUrl();
  if (existingUrl) return { url: existingUrl, stop: () => undefined };
  if (process.env.ATLAS_LOOP_ASK_USE_IMAGE_WORKER === 'false') return null;
  return await startImageWorker();
}

function currentImageWorkerUrl() {
  return process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL ?? process.env.ATLAS_OPENCLAW_IMAGE_WORKER_URL;
}

async function startImageWorker() {
  const workerEnv = {
    ...process.env,
    ATLAS_OPENCLAW_IMAGE_WORKER_MODEL: process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_MODEL ?? 'openai-codex/gpt-5.5',
    ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM: process.env.ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM ?? '1'
  };
  const child = spawn(process.execPath, [imageWorkerPath], { cwd: repoRoot, env: sanitizeEnv(workerEnv), stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const timeoutMs = Number(process.env.ATLAS_OPENCLAW_IMAGE_WORKER_START_TIMEOUT_MS ?? 120000);
  const { url } = await waitForWorkerReady(child, timeoutMs);
  process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL = url;
  process.env.ATLAS_OPENCLAW_IMAGE_WORKER_URL = url;
  return { url, stop: () => stopChild(child) };
}

async function waitForWorkerReady(child, timeoutMs) {
  return await new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    const timeout = setTimeout(() => {
      stopChild(child);
      reject(new Error(`OpenClaw image worker did not become ready after ${timeoutMs}ms.${stderr ? ` stderr: ${stderr.trim()}` : ''}`));
    }, timeoutMs);

    const cleanup = () => {
      clearTimeout(timeout);
      child.stdout.off('data', onStdout);
      child.stderr.off('data', onStderr);
      child.off('error', onError);
      child.off('exit', onExit);
    };
    const onStdout = (chunk) => {
      stdout += chunk;
      for (const line of stdout.split(/\r?\n/)) {
        if (!line.trim().startsWith('{')) continue;
        try {
          const parsed = JSON.parse(line);
          if (parsed.kind === 'atlas.openclaw-image-worker.ready' && parsed.url) {
            cleanup();
            resolve(parsed);
            return;
          }
        } catch {
          // Keep waiting for a JSON ready line.
        }
      }
    };
    const onStderr = (chunk) => {
      stderr += chunk;
    };
    const onError = (error) => {
      cleanup();
      reject(error);
    };
    const onExit = (code) => {
      cleanup();
      reject(new Error(`OpenClaw image worker exited before ready with code ${code}.${stderr ? ` stderr: ${stderr.trim()}` : ''}`));
    };

    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', onStdout);
    child.stderr.on('data', onStderr);
    child.on('error', onError);
    child.on('exit', onExit);
  });
}

function stopChild(child) {
  if (!child || child.killed) return;
  child.kill();
}

async function ensureConfiguredSession() {
  const inspect = await atlas(['session', 'inspect', session, '--store', store], { allowFailure: true });
  if (inspect.code === 0) return;
  await atlas(['session', 'create', session, '--config', configPath, '--store', store]);
  await atlas(['session', 'start', session, '--store', store]);
}

async function printSummary() {
  const entries = await readJsonlEntries(jsonlPath);
  if (entries.length === 0) {
    console.log(`No loop summary found yet: ${jsonlPath}`);
    return;
  }

  if (args.markdown === true) {
    const text = await readTextIfExists(summaryPath);
    if (!text) {
      console.log(`No Markdown loop summary found yet: ${summaryPath}`);
      return;
    }
    const lines = text.trimEnd().split(/\r?\n/);
    const tail = args.tail ?? 120;
    console.log(lines.slice(-tail).join('\n'));
    return;
  }

  printParsedSummary(entries);
}

function printParsedSummary(entries) {
  const captures = entries.filter((entry) => entry.captured).length;
  const reuses = entries.length - captures;
  const wallValues = entries.map((entry) => entry.wallMs).filter(isFiniteNumber);
  const capturedWallValues = entries.filter((entry) => entry.captured).map((entry) => entry.wallMs).filter(isFiniteNumber);
  const reuseWallValues = entries.filter((entry) => !entry.captured).map((entry) => entry.wallMs).filter(isFiniteNumber);
  const bridgeTotalValues = entries.map((entry) => entry.timing?.bridge?.totalMs).filter(isFiniteNumber);
  const captureValues = entries.map((entry) => entry.timing?.bridge?.captureMs).filter(isFiniteNumber);
  const analysisValues = entries.map((entry) => entry.timing?.bridge?.analysisMs).filter(isFiniteNumber);
  const latest = entries.at(-1);

  console.log('Atlas loop summary');
  console.log(`- ticks: ${entries.length}`);
  console.log(`- captures: ${captures}`);
  console.log(`- reuses: ${reuses}`);
  console.log(`- avg wall: ${formatMs(avg(wallValues))}`);
  if (capturedWallValues.length) console.log(`- avg captured wall: ${formatMs(avg(capturedWallValues))}`);
  if (reuseWallValues.length) console.log(`- avg reuse wall: ${formatMs(avg(reuseWallValues))}`);
  if (bridgeTotalValues.length) console.log(`- avg bridge: ${formatMs(avg(bridgeTotalValues))}`);
  if (captureValues.length) console.log(`- avg camera/helper capture: ${formatMs(avg(captureValues))}`);
  if (analysisValues.length) console.log(`- avg image analysis: ${formatMs(avg(analysisValues))}`);
  console.log(`- significance: ${formatCounts(countBy(entries, (entry) => entry.significance?.level ?? 'none'))}`);
  console.log(`- cadence: ${formatCounts(countBy(entries, (entry) => entry.cadence?.mode ?? 'unknown'))}`);
  console.log(`- latest tick: ${latest?.tick ?? 'n/a'} at ${latest?.at ?? 'n/a'}`);
  console.log(`- latest capture: ${latest?.captured ? 'yes' : 'no'}`);
  console.log(`- latest significance: ${latest?.significance?.level ?? 'none'}${isFiniteNumber(latest?.significance?.score) ? ` score=${latest.significance.score.toFixed(2)}` : ''}`);
  if (latest?.decision?.reason) console.log(`- latest decision: ${latest.decision.reason}`);
  if (latest?.mediaRef) console.log(`- latest media: ${latest.mediaRef}`);
  console.log(`- jsonl: ${jsonlPath}`);
  console.log(`- markdown: ${summaryPath}`);
  console.log('');
  console.log(`Latest summary: ${latest?.summary ?? '(none)'}`);
}

async function readControl() {
  try {
    return JSON.parse(await readFile(controlPath, 'utf8'));
  } catch {
    return null;
  }
}

async function writeControl(value) {
  await mkdir(path.dirname(controlPath), { recursive: true });
  await writeFile(controlPath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

async function openAppend(filePath) {
  await mkdir(path.dirname(filePath), { recursive: true });
  return await import('node:fs').then((fs) => fs.openSync(filePath, 'a'));
}

function isProcessAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function stopProcess(pid) {
  if (process.platform === 'win32') {
    await run('taskkill', ['/PID', String(pid), '/T', '/F']);
    return;
  }
  process.kill(pid, 'SIGTERM');
}

async function atlas(cliArgs, options = {}) {
  const result = await runProcess(process.execPath, [path.join(repoRoot, 'packages', 'atlas-cli', 'dist', 'index.js'), ...cliArgs]);
  if (result.code !== 0 && !options.allowFailure) {
    throw new Error([`atlas ${cliArgs.join(' ')} failed with code ${result.code}`, result.stdout, result.stderr].filter(Boolean).join('\n'));
  }
  return options.allowFailure ? result : result.stdout;
}

async function atlasJson(cliArgs) {
  return JSON.parse(await atlas(cliArgs));
}

async function run(command, commandArgs) {
  await runProcess(command, commandArgs, { rejectOnFailure: true });
}

async function runProcess(command, commandArgs, options = {}) {
  return await new Promise((resolve, reject) => {
    const child = spawn(command, commandArgs, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => (stdout += chunk));
    child.stderr.on('data', (chunk) => (stderr += chunk));
    child.on('error', reject);
    child.on('close', (code) => {
      const result = { code, stdout, stderr };
      if (code === 0 || !options.rejectOnFailure) resolve(result);
      else reject(new Error(`${command} ${commandArgs.join(' ')} failed with code ${code}${stderr ? `: ${stderr.trim()}` : ''}`));
    });
  });
}

async function countJsonlLines(filePath) {
  const text = await readTextIfExists(filePath);
  if (!text) return 0;
  return text.split(/\r?\n/).filter(Boolean).length;
}

async function readJsonlEntries(filePath) {
  const text = await readTextIfExists(filePath);
  if (!text) return [];
  return text.split(/\r?\n/).filter(Boolean).map((line, index) => {
    try {
      return JSON.parse(line);
    } catch (error) {
      throw new Error(`Invalid JSONL at ${filePath}:${index + 1}: ${error instanceof Error ? error.message : String(error)}`);
    }
  });
}

async function readTextIfExists(filePath) {
  if (!existsSync(filePath)) return '';
  return await readFile(filePath, 'utf8');
}

function parseArgs(raw) {
  const parsed = { _: [] };
  for (let index = 0; index < raw.length; index += 1) {
    const arg = raw[index];
    if (arg === '--ticks') parsed.ticks = readPositiveInteger(raw[++index], '--ticks');
    else if (arg === '--max-sleep-ms') parsed.maxSleepMs = readPositiveInteger(raw[++index], '--max-sleep-ms');
    else if (arg === '--session') parsed.session = raw[++index];
    else if (arg === '--store') parsed.store = raw[++index];
    else if (arg === '--tail') parsed.tail = readPositiveInteger(raw[++index], '--tail');
    else if (arg === '--markdown') parsed.markdown = true;
    else if (arg === '--text') parsed.text = raw[++index] ?? '';
    else if (arg === '--provider-mode') parsed.providerMode = raw[++index] ?? '';
    else if (arg === '--image-worker') parsed.imageWorker = readImageWorkerMode(raw[++index]);
    else parsed._.push(arg);
  }
  return parsed;
}

function readPositiveInteger(value, flag) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1) throw new Error(`${flag} must be a positive integer`);
  return parsed;
}

function readImageWorkerMode(value) {
  if (value === 'auto' || value === 'true' || value === 'false') return value;
  throw new Error('--image-worker must be one of: auto, true, false');
}

function sanitizeEnv(env) {
  return Object.fromEntries(Object.entries(env).filter((entry) => typeof entry[1] === 'string'));
}

function isFiniteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function avg(values) {
  if (!values.length) return undefined;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function countBy(values, keyFn) {
  const counts = new Map();
  for (const value of values) {
    const key = keyFn(value);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

function formatCounts(counts) {
  return [...counts.entries()].map(([key, count]) => `${key}=${count}`).join(', ') || 'none';
}

function latestBridgeTiming(inspection) {
  const latest = inspection?.observations?.latest;
  const timings = latest?.data?.bridge?.timings;
  const latency = latest?.telemetry?.latencyMs;
  const totalMs = timings?.totalMs ?? latency?.total;
  const captureMs = timings?.captureMs ?? latency?.capture;
  const stageMs = timings?.stageMs ?? latency?.stage;
  const analysisMs = timings?.analysisMs ?? latency?.analysis;
  if (![totalMs, captureMs, stageMs, analysisMs].some(isFiniteNumber)) return null;
  return { totalMs, captureMs, stageMs, analysisMs };
}

function formatMs(value) {
  if (!isFiniteNumber(value)) return 'n/a';
  if (value < 1000) return `${Math.round(value)}ms`;
  return `${(value / 1000).toFixed(2)}s`;
}

function helpText() {
  return `Atlas Android loop control\n\nUsage:\n  npm run loop:android -- start [--ticks 9999] [--max-sleep-ms 30000]\n  npm run loop:android -- fresh-start [--ticks 9999] [--max-sleep-ms 30000]\n  npm run loop:android -- stop\n  npm run loop:android -- status\n  npm run loop:android -- summary [--markdown] [--tail 120]\n  npm run loop:android -- ask \"What am I looking at?\"\n\nDefaults to session live-android-openclaw and store .atlas-runs/latest-ambient-android. start resumes the stable loop location; fresh-start clears that store first. summary parses ambient-loop.jsonl by default; use --markdown to tail ambient-loop.md. ask uses the stable session/store and summary provider mode by default. Logs are written under <store>/<session>/.`;
}
