#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { mkdtemp } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const cliPath = path.join(repoRoot, 'packages', 'atlas-cli', 'dist', 'index.js');
const configPath = path.join(repoRoot, 'examples', 'android-openclaw-basic', 'atlas-live.config.example.json');
const workerPath = path.join(repoRoot, 'examples', 'android-openclaw-basic', 'openclaw-image-worker.mjs');
const sessionId = process.env.ATLAS_LIVE_SESSION_ID ?? 'live-android-openclaw';
const demoArgs = process.argv.slice(2);
const useSummaryProvider = demoArgs.includes('--fast') || demoArgs.includes('--summary-provider') || process.env.ATLAS_LIVE_PROVIDER_MODE === 'summary';
const promptArgs = demoArgs.filter((arg) => arg !== '--fast' && arg !== '--summary-provider');
const text = promptArgs.join(' ') || process.env.ATLAS_LIVE_TEXT || 'What am I looking at?';
const storeRoot = process.env.ATLAS_STORE || (await mkdtemp(path.join(os.tmpdir(), 'atlas-live-demo-')));
const shouldStartImageWorker = process.env.ATLAS_LIVE_USE_IMAGE_WORKER !== 'false'
  && !process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL
  && !process.env.ATLAS_OPENCLAW_IMAGE_WORKER_URL;
if (useSummaryProvider) process.env.ATLAS_OPENCLAW_PROVIDER_MODE = 'summary';

let imageWorker;
try {
  console.log('Atlas live Android/OpenClaw demo');
  console.log(`Session: ${sessionId}`);
  console.log(`Store: ${storeRoot}`);
  console.log(`Prompt: ${text}`);
  console.log(`Provider mode: ${useSummaryProvider ? 'summary fast path' : 'OpenClaw agent'}`);
  if (shouldStartImageWorker) {
    imageWorker = await startImageWorker();
    process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL = imageWorker.url;
    console.log(`OpenClaw image worker: ${imageWorker.url}`);
  } else if (process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL || process.env.ATLAS_OPENCLAW_IMAGE_WORKER_URL) {
    console.log(`OpenClaw image worker: ${process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL ?? process.env.ATLAS_OPENCLAW_IMAGE_WORKER_URL}`);
  }
  console.log('');

  await atlas(['session', 'create', sessionId, '--config', configPath, '--store', storeRoot]);
  await atlas(['session', 'start', sessionId, '--store', storeRoot]);
  const ask = await atlasJson(['session', 'ask', sessionId, '--text', text, '--config', configPath, '--store', storeRoot]);
  const inspection = await atlasJson(['session', 'inspect', sessionId, '--store', storeRoot]);

  console.log('Response:');
  console.log(ask.responseText ?? '(no response text)');
  console.log('');
  printTimingReport(inspection);
  console.log('');
  console.log(`Latest observation: ${inspection.observations?.latest?.id ?? '(none)'}`);
  if (inspection.observations?.latest?.mediaRef) console.log(`Media: ${inspection.observations.latest.mediaRef}`);
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  console.error(message);
  if (message.includes('NODE_BACKGROUND_UNAVAILABLE')) {
    console.error('\nHint: foreground the OpenClaw Android app on the phone, then rerun the live demo.');
  }
  process.exitCode = 1;
} finally {
  imageWorker?.stop();
}

async function startImageWorker() {
  const workerEnv = {
    ...process.env,
    ATLAS_OPENCLAW_IMAGE_WORKER_MODEL: process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_MODEL ?? 'openai-codex/gpt-5.5',
    ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM: process.env.ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM ?? '1'
  };
  const child = spawn(process.execPath, [workerPath], { cwd: repoRoot, env: workerEnv, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const timeoutMs = Number(process.env.ATLAS_OPENCLAW_IMAGE_WORKER_START_TIMEOUT_MS ?? 120000);
  const { url } = await waitForWorkerReady(child, timeoutMs);
  return {
    url,
    stop: () => stopChild(child)
  };
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

async function atlas(args) {
  const result = await run(process.execPath, [cliPath, ...args]);
  if (result.code !== 0) {
    throw new Error([`atlas ${args.join(' ')} failed with code ${result.code}`, result.stdout, result.stderr].filter(Boolean).join('\n'));
  }
  return result.stdout;
}

async function atlasJson(args) {
  return JSON.parse(await atlas(args));
}

async function run(command, args) {
  return await new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: repoRoot, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
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

function printTimingReport(inspection) {
  const timing = inspection.timing ?? {};
  const bridge = timing.bridge ?? {};
  console.log('Timing report:');
  printDuration('Total user turn', timing.userTurnMs);
  printDuration('Atlas capture round trip', timing.captureRoundTripMs);
  printDuration('Bridge total', bridge.totalMs);
  printDuration('  camera/helper capture', bridge.captureMs);
  printDuration('  file stage', bridge.stageMs);
  printDuration('  image analysis', bridge.analysisMs);
  printDuration('Provider round trip', timing.providerRoundTripMs);
}

function printDuration(label, value) {
  console.log(`- ${label}: ${formatMs(value)}`);
}

function formatMs(value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return 'n/a';
  if (value < 1000) return `${value}ms`;
  return `${(value / 1000).toFixed(2)}s`;
}
