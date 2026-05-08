#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { appendFile, mkdir, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const cliPath = path.join(repoRoot, 'packages', 'atlas-cli', 'dist', 'index.js');
const workerPath = path.join(repoRoot, 'examples', 'android-openclaw-basic', 'openclaw-image-worker.mjs');
const args = parseArgs(process.argv.slice(2));
const sessionId = args.session ?? process.env.ATLAS_AMBIENT_SESSION_ID ?? 'ambient-loop-fake';
const storeRoot = args.store ?? process.env.ATLAS_STORE ?? path.join(os.tmpdir(), `atlas-ambient-loop-${Date.now()}`);
const ticks = args.ticks ?? 3;
const shouldWait = args.wait === true;
const maxSleepMs = args.maxSleepMs ?? 5_000;
const logDir = path.join(storeRoot, sessionId);
const jsonlPath = args.jsonl ?? path.join(logDir, 'ambient-loop.jsonl');
const markdownPath = args.markdown ?? path.join(logDir, 'ambient-loop.md');
const askText = args.askText ?? process.env.ATLAS_AMBIENT_ASK_TEXT;

let stopped = false;
let imageWorker;
const requestStop = () => {
  stopped = true;
  console.log('\nStopping ambient loop after current tick...');
};
process.on('SIGINT', requestStop);
process.on('SIGTERM', requestStop);

try {
  console.log('Atlas ambient loop runner');
  console.log(`Session: ${sessionId}`);
  console.log(`Store: ${storeRoot}`);
  console.log(`Ticks: ${ticks}`);
  console.log(`Wait: ${shouldWait ? `yes (max ${maxSleepMs}ms)` : 'no'}`);
  console.log(`JSONL: ${jsonlPath}`);
  console.log(`Markdown: ${markdownPath}`);
  if (askText) console.log(`Cached ask after ticks: ${askText}`);
  if (args.config) console.log(`Config: ${args.config}`);
  else console.log('Config: none (uses built-in fake adapters; no phone camera)');

  const shouldUseImageWorker = await shouldStartImageWorker(args);
  if (shouldUseImageWorker) {
    imageWorker = await startImageWorker();
    process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL = imageWorker.url;
    process.env.ATLAS_OPENCLAW_IMAGE_WORKER_URL = imageWorker.url;
    console.log(`OpenClaw image worker: ${imageWorker.url}`);
    if (imageWorker.warm) console.log(`OpenClaw image worker warm: ${formatWarmState(imageWorker.warm)}`);
  } else if (currentImageWorkerUrl()) {
    console.log(`OpenClaw image worker: ${currentImageWorkerUrl()}`);
  }
  console.log('');

  await mkdir(logDir, { recursive: true });
  await ensureSession();
  await atlas(['session', 'start', sessionId, '--store', storeRoot]);

  for (let tick = 1; tick <= ticks && !stopped; tick += 1) {
    const started = Date.now();
    const heartbeatArgs = ['session', 'heartbeat', sessionId, '--store', storeRoot];
    if (args.config) heartbeatArgs.push('--config', args.config);
    const heartbeat = await atlasJson(heartbeatArgs);
    const wallMs = Date.now() - started;
    const inspection = await atlasJson(['session', 'inspect', sessionId, '--store', storeRoot]);
    const entry = buildEntry({ tick, wallMs, heartbeat, inspection });

    await appendLoopLogs(entry);
    printEntry(entry);

    if (shouldWait && tick < ticks && !stopped) {
      await sleep(Math.min(entry.cadence.nextDelayMs ?? 0, maxSleepMs));
    }
  }

  if (askText && !stopped) {
    const askEntry = await runCachedAsk(askText);
    await appendLoopLogs(askEntry);
    printAskEntry(askEntry);
  }

  console.log('');
  console.log(`Wrote loop summaries to:\n- ${jsonlPath}\n- ${markdownPath}`);
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
} finally {
  imageWorker?.stop();
}

async function ensureSession() {
  const inspect = await atlas(['session', 'inspect', sessionId, '--store', storeRoot], { allowFailure: true });
  if (inspect.code === 0) return;

  const createArgs = ['session', 'create', sessionId, '--store', storeRoot];
  if (args.config) {
    createArgs.push('--config', args.config);
  } else {
    createArgs.push('--device', 'fake-camera:@atlas/core/testing:camera.capture');
  }
  await atlas(createArgs);
}

function buildEntry({ tick, wallMs, heartbeat, inspection }) {
  const latest = inspection.observations?.latest;
  const significance = heartbeat.significance;
  return {
    type: 'heartbeat',
    tick,
    at: new Date().toISOString(),
    wallMs,
    sessionId,
    captured: Boolean(heartbeat.observationId),
    observationId: heartbeat.observationId,
    summary: latest?.summary ?? inspection.perception?.summary,
    decision: {
      shouldCapture: heartbeat.decision?.shouldCapture,
      reason: heartbeat.decision?.reason
    },
    freshness: heartbeat.decision?.freshness,
    cadence: {
      mode: heartbeat.decision?.cadence?.mode,
      nextDelayMs: heartbeat.decision?.cadence?.nextDelayMs,
      reason: heartbeat.decision?.cadence?.reason
    },
    captureBudget: heartbeat.decision?.captureBudget,
    fallback: heartbeat.decision?.fallback,
    significance: significance
      ? {
          level: significance.level,
          score: significance.score,
          shouldCallProvider: significance.shouldCallProvider,
          shouldNotifyUser: significance.shouldNotifyUser,
          reason: significance.reason,
          signals: significance.signals
        }
      : undefined,
    providerReview: heartbeat.providerResult
      ? {
          responseText: heartbeat.providerResult.responseText,
          done: heartbeat.providerResult.done,
          proactiveSpeechSuppressed: heartbeat.proactiveSpeechSuppressed
        }
      : undefined,
    timing: inspection.timing,
    mediaRef: latest?.mediaRef
  };
}

async function runCachedAsk(text) {
  const started = Date.now();
  const askArgs = ['session', 'ask', sessionId, '--store', storeRoot, '--text', text];
  if (args.config) askArgs.push('--config', args.config);
  const ask = await atlasJson(askArgs);
  const wallMs = Date.now() - started;
  const inspection = await atlasJson(['session', 'inspect', sessionId, '--store', storeRoot]);
  const latest = inspection.observations?.latest;

  return {
    type: 'cached-ask',
    at: new Date().toISOString(),
    wallMs,
    sessionId,
    text,
    responseText: ask.responseText,
    plan: {
      needsVisualContext: ask.plan?.needsVisualContext,
      shouldRefreshVisualContext: ask.plan?.shouldRefreshVisualContext,
      reason: ask.plan?.reason,
      useCase: ask.plan?.useCase,
      confidence: ask.plan?.confidence
    },
    refreshedObservationId: ask.refreshedObservationId,
    refreshError: ask.refreshError,
    reusedLastObservationAfterRefreshFailure: ask.reusedLastObservationAfterRefreshFailure,
    context: {
      latestObservationId: latest?.id,
      summary: latest?.summary ?? inspection.perception?.summary,
      freshnessMs: inspection.perception?.freshnessMs,
      confidence: inspection.perception?.confidence,
      stability: inspection.perception?.stability,
      mediaRef: latest?.mediaRef
    },
    timing: inspection.timing
  };
}

async function appendLoopLogs(entry) {
  await appendFile(jsonlPath, `${JSON.stringify(entry)}\n`, 'utf8');
  await appendFile(markdownPath, formatMarkdownEntry(entry), 'utf8');
}

async function shouldStartImageWorker(parsedArgs) {
  if (parsedArgs.imageWorker === 'false') return false;
  if (currentImageWorkerUrl()) return false;
  if (parsedArgs.imageWorker === 'true') return true;
  if (process.env.ATLAS_AMBIENT_LOOP_USE_IMAGE_WORKER === 'false') return false;
  if (!parsedArgs.config) return false;
  return await configUsesOpenClawImageBridge(parsedArgs.config);
}

function currentImageWorkerUrl() {
  return process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL ?? process.env.ATLAS_OPENCLAW_IMAGE_WORKER_URL;
}

async function configUsesOpenClawImageBridge(configPath) {
  try {
    const absolute = path.resolve(repoRoot, configPath);
    const config = JSON.parse(await readFile(absolute, 'utf8'));
    const sessions = typeof config?.sessions === 'object' && config.sessions !== null ? Object.values(config.sessions) : [];
    return sessions.some((session) => {
      const devices = Array.isArray(session?.devices) ? session.devices : [];
      return devices.some((device) => {
        const deviceConfig = device?.config ?? {};
        const commandArgs = Array.isArray(deviceConfig.args) ? deviceConfig.args.join(' ') : '';
        return device?.adapter === '@atlas/device-android/bridge-command'
          && deviceConfig.analysisMode === 'openclaw'
          && commandArgs.includes('bridge-wrapper.mjs');
      });
    });
  } catch {
    return false;
  }
}

async function startImageWorker() {
  const shouldPrewarm = parseBoolean(process.env.ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM, false);
  const workerEnv = {
    ...process.env,
    ATLAS_OPENCLAW_IMAGE_WORKER_MODEL: process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_MODEL ?? 'openai-codex/gpt-5.5',
    ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM: '0'
  };
  const child = spawn(process.execPath, [workerPath], { cwd: repoRoot, env: workerEnv, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const timeoutMs = Number(process.env.ATLAS_OPENCLAW_IMAGE_WORKER_START_TIMEOUT_MS ?? 180000);
  const { url } = await waitForWorkerReady(child, timeoutMs);
  try {
    const warm = shouldPrewarm ? await warmImageWorker(url, workerEnv) : undefined;
    return { url, warm, stop: () => stopChild(child) };
  } catch (error) {
    stopChild(child);
    throw error;
  }
}

async function warmImageWorker(url, workerEnv) {
  const timeoutMs = Number(process.env.ATLAS_OPENCLAW_IMAGE_WORKER_WARM_TIMEOUT_MS ?? workerEnv.ATLAS_OPENCLAW_IMAGE_WORKER_TIMEOUT_MS ?? 180000);
  const response = await postJson(new URL('/warm', url), {
    model: workerEnv.ATLAS_OPENCLAW_IMAGE_WORKER_MODEL,
    timeoutMs
  }, { timeoutMs });
  return response?.warm;
}

async function postJson(url, body, options = {}) {
  const controller = new AbortController();
  const timeout = options.timeoutMs ? setTimeout(() => controller.abort(new Error(`request timed out after ${options.timeoutMs}ms`)), options.timeoutMs) : undefined;
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal
    });
    const text = await response.text();
    const json = text.trim() ? JSON.parse(text) : {};
    if (!response.ok || json?.ok === false) throw new Error(json?.error ?? `HTTP ${response.status}: ${text}`);
    return json;
  } finally {
    if (timeout) clearTimeout(timeout);
  }
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

function formatMarkdownEntry(entry) {
  if (entry.type === 'cached-ask') return formatMarkdownAskEntry(entry);

  const lines = [
    `## Tick ${entry.tick} — ${entry.at}`,
    '',
    `- cadence: ${entry.cadence.mode ?? 'unknown'} next=${formatMs(entry.cadence.nextDelayMs)} (${entry.cadence.reason ?? 'no reason'})`,
    `- freshness: age=${formatMs(entry.freshness?.contextAgeMs)} staleAfter=${formatMs(entry.freshness?.staleAfterMs)} multiplier=${entry.freshness?.multiplier ?? '?'} refreshDue=${entry.freshness?.refreshDue ?? '?'} stale=${entry.freshness?.stale ?? '?'}`,
    `- refresh deadline: dueAt=${formatTimeMs(entry.freshness?.refreshDueAtMs)} staleAt=${formatTimeMs(entry.freshness?.staleAtMs)} expectedLatency=${formatMs(entry.freshness?.expectedRefreshLatencyMs)} safety=${formatMs(entry.freshness?.safetyMarginMs)}`,
    entry.freshness?.signals?.length ? `- freshness signals: ${entry.freshness.signals.join(', ')}` : undefined,
    `- capture budget: ${entry.captureBudget ? `${entry.captureBudget.status} (${entry.captureBudget.capturesLastMinute}/min, ${entry.captureBudget.capturesLastFiveMinutes}/5min${entry.captureBudget.averageCaptureLatencyMs ? `, avg ${formatMs(entry.captureBudget.averageCaptureLatencyMs)}` : ''})` : 'n/a'}`,
    entry.fallback ? `- fallback: ${entry.fallback.kind} refresh=${entry.fallback.refreshHealth} retryDue=${entry.fallback.retryDue} retryAfter=${formatMs(entry.fallback.retryAfterMs)}` : undefined,
    `- capture: ${entry.captured ? `yes (${entry.observationId})` : 'no'}`,
    `- decision: ${entry.decision.reason ?? 'n/a'}`,
    `- significance: ${entry.significance ? `${entry.significance.level} score=${formatScore(entry.significance.score)} provider=${entry.significance.shouldCallProvider} notify=${entry.significance.shouldNotifyUser}` : 'not assessed'}`,
    entry.providerReview ? `- provider review: yes${entry.providerReview.proactiveSpeechSuppressed ? ' (speech suppressed)' : ''}` : undefined,
    `- wall time: ${formatMs(entry.wallMs)}`,
    entry.mediaRef ? `- media: ${entry.mediaRef}` : undefined,
    '',
    entry.summary ? `summary: ${entry.summary}` : 'summary: (none)',
    entry.providerReview?.responseText ? `provider review: ${entry.providerReview.responseText}` : undefined,
    '',
    ''
  ].filter((line) => line !== undefined);
  return `${lines.join('\n')}`;
}

function formatMarkdownAskEntry(entry) {
  const lines = [
    `## Cached ask — ${entry.at}`,
    '',
    `- text: ${entry.text}`,
    `- wall time: ${formatMs(entry.wallMs)}`,
    `- refresh during ask: ${entry.refreshedObservationId ? `yes (${entry.refreshedObservationId})` : 'no'}`,
    `- plan: visual=${entry.plan?.needsVisualContext ?? '?'} refresh=${entry.plan?.shouldRefreshVisualContext ?? '?'} useCase=${entry.plan?.useCase ?? 'unknown'} confidence=${formatScore(entry.plan?.confidence)}`,
    `- reason: ${entry.plan?.reason ?? 'n/a'}`,
    entry.refreshError ? `- refresh error: ${entry.refreshError}` : undefined,
    entry.context?.latestObservationId ? `- latest observation: ${entry.context.latestObservationId}` : undefined,
    entry.context?.mediaRef ? `- media: ${entry.context.mediaRef}` : undefined,
    '',
    entry.context?.summary ? `context summary: ${entry.context.summary}` : 'context summary: (none)',
    '',
    entry.responseText ? `response: ${entry.responseText}` : 'response: (none)',
    '',
    ''
  ].filter((line) => line !== undefined);
  return `${lines.join('\n')}`;
}

function printEntry(entry) {
  console.log(
    [
      `tick ${entry.tick}`,
      `cadence=${entry.cadence.mode ?? 'unknown'}`,
      `age=${formatMs(entry.freshness?.contextAgeMs)}/${formatMs(entry.freshness?.staleAfterMs)}`,
      `refreshDue=${entry.freshness?.refreshDue === true ? 'yes' : 'no'}`,
      `budget=${entry.captureBudget?.status ?? 'n/a'}`,
      `capture=${entry.captured ? 'yes' : 'no'}`,
      `significance=${entry.significance?.level ?? 'n/a'}`,
      `provider=${entry.providerReview ? 'yes' : 'no'}`,
      `wall=${formatMs(entry.wallMs)}`,
      entry.summary ? `summary=${truncate(entry.summary, 120)}` : 'summary=(none)'
    ].join(' | ')
  );
}

function printAskEntry(entry) {
  console.log(
    [
      'cached ask',
      `refresh=${entry.refreshedObservationId ? 'yes' : 'no'}`,
      `wall=${formatMs(entry.wallMs)}`,
      entry.context?.summary ? `context=${truncate(entry.context.summary, 100)}` : 'context=(none)',
      entry.responseText ? `response=${truncate(entry.responseText, 120)}` : 'response=(none)'
    ].join(' | ')
  );
}

async function atlas(cliArgs, options = {}) {
  const result = await run(process.execPath, [cliPath, ...cliArgs]);
  if (result.code !== 0 && !options.allowFailure) {
    throw new Error([`atlas ${cliArgs.join(' ')} failed with code ${result.code}`, result.stdout, result.stderr].filter(Boolean).join('\n'));
  }
  return options.allowFailure ? result : result.stdout;
}

async function atlasJson(cliArgs) {
  return JSON.parse(await atlas(cliArgs));
}

async function run(command, cliArgs) {
  return await new Promise((resolve, reject) => {
    const child = spawn(command, cliArgs, { cwd: repoRoot, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => (stdout += chunk));
    child.stderr.on('data', (chunk) => (stderr += chunk));
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
  });
}

function parseArgs(raw) {
  const parsed = {};
  for (let index = 0; index < raw.length; index += 1) {
    const arg = raw[index];
    if (arg === '--session') parsed.session = raw[++index];
    else if (arg === '--store') parsed.store = raw[++index];
    else if (arg === '--config') parsed.config = raw[++index];
    else if (arg === '--ticks') parsed.ticks = readPositiveInteger(raw[++index], '--ticks');
    else if (arg === '--wait') parsed.wait = true;
    else if (arg === '--max-sleep-ms') parsed.maxSleepMs = readPositiveInteger(raw[++index], '--max-sleep-ms');
    else if (arg === '--jsonl') parsed.jsonl = raw[++index];
    else if (arg === '--markdown') parsed.markdown = raw[++index];
    else if (arg === '--ask-text') parsed.askText = raw[++index];
    else if (arg === '--image-worker') parsed.imageWorker = readImageWorkerMode(raw[++index]);
    else if (arg === '--help' || arg === '-h') {
      console.log(helpText());
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}\n\n${helpText()}`);
    }
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

function parseBoolean(value, fallback) {
  if (typeof value !== 'string' || !value.trim()) return fallback;
  if (/^(1|true|yes|on)$/i.test(value)) return true;
  if (/^(0|false|no|off)$/i.test(value)) return false;
  return fallback;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
}

function truncate(text, max) {
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

function formatScore(score) {
  return typeof score === 'number' && Number.isFinite(score) ? score.toFixed(2) : '?';
}

function formatMs(value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return 'n/a';
  if (value < 1000) return `${value}ms`;
  return `${(value / 1000).toFixed(2)}s`;
}

function formatTimeMs(value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return 'n/a';
  return new Date(value).toISOString();
}

function formatWarmState(warm) {
  if (!warm) return 'n/a';
  const parts = [warm.status ?? 'unknown'];
  if (warm.model) parts.push(warm.model);
  if (typeof warm.durationMs === 'number') parts.push(`in ${formatMs(warm.durationMs)}`);
  if (warm.error) parts.push(`error=${warm.error}`);
  return parts.join(' ');
}

function helpText() {
  return `Atlas ambient loop runner\n\nUsage:\n  node examples/ambient-loop.mjs [--ticks 3] [--wait] [--max-sleep-ms 5000]\n                                 [--session id] [--store path] [--config path]\n                                 [--jsonl path] [--markdown path]\n                                 [--ask-text "What am I looking at?"]\n                                 [--image-worker auto|true|false]\n\nDefault mode uses built-in fake adapters and does not invoke the phone camera. Passing a live Android config will invoke whatever device adapter that config selects. With --ask-text, the runner finishes heartbeat ticks with one explicit ask so the log proves whether the user turn reused ambient context or triggered an ask-time refresh. With --image-worker auto, OpenClaw image worker starts automatically for the Android/OpenClaw bridge config.`;
}
