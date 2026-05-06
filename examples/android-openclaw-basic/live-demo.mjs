#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { mkdtemp } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const cliPath = path.join(repoRoot, 'packages', 'atlas-cli', 'dist', 'index.js');
const configPath = path.join(repoRoot, 'examples', 'android-openclaw-basic', 'atlas-live.config.example.json');
const sessionId = process.env.ATLAS_LIVE_SESSION_ID ?? 'live-android-openclaw';
const text = process.argv.slice(2).join(' ') || process.env.ATLAS_LIVE_TEXT || 'What am I looking at?';
const storeRoot = process.env.ATLAS_STORE || (await mkdtemp(path.join(os.tmpdir(), 'atlas-live-demo-')));

try {
  console.log('Atlas live Android/OpenClaw demo');
  console.log(`Session: ${sessionId}`);
  console.log(`Store: ${storeRoot}`);
  console.log(`Prompt: ${text}`);
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
