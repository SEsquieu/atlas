#!/usr/bin/env node
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const ambientLoopPath = path.join(repoRoot, 'examples', 'ambient-loop.mjs');
const configPath = path.join(repoRoot, 'examples', 'android-openclaw-basic', 'atlas-live.config.example.json');
const args = parseArgs(process.argv.slice(2));
const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const shouldBuild = args.build !== false;
const ticks = String(args.ticks ?? 3);
const maxSleepMs = String(args.maxSleepMs ?? 30_000);
const session = args.session ?? process.env.ATLAS_AMBIENT_SESSION_ID ?? 'live-android-openclaw';
const loopArgs = [ambientLoopPath, '--config', configPath, '--session', session, '--ticks', ticks, '--image-worker', args.imageWorker ?? 'auto'];

if (args.wait !== false) loopArgs.push('--wait', '--max-sleep-ms', maxSleepMs);
if (args.store) loopArgs.push('--store', args.store);
if (args.jsonl) loopArgs.push('--jsonl', args.jsonl);
if (args.markdown) loopArgs.push('--markdown', args.markdown);

try {
  console.log('Atlas ambient Android/OpenClaw loop smoke');
  console.log(`Session: ${session}`);
  console.log(`Ticks: ${ticks}`);
  console.log(`Wait: ${args.wait !== false ? `yes (max ${maxSleepMs}ms)` : 'no'}`);
  console.log(`Build: ${shouldBuild ? 'yes' : 'skipped'}`);
  console.log('Note: this uses the live Android/OpenClaw config and may invoke the phone camera during heartbeat ticks.');
  console.log('');

  if (shouldBuild) await runChecked(npmCommand, ['run', 'build'], 'build');
  await runChecked(process.execPath, loopArgs, 'ambient Android loop');
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}

async function runChecked(command, commandArgs, label) {
  const startedAt = Date.now();
  console.log(`==> ${label}`);
  const code = await run(command, commandArgs);
  if (code !== 0) throw new Error(`${label} failed with code ${code}`);
  console.log(`==> ${label} passed in ${formatMs(Date.now() - startedAt)}`);
  console.log('');
}

async function run(command, commandArgs) {
  return await new Promise((resolve, reject) => {
    const child = spawn(command, commandArgs, {
      cwd: repoRoot,
      env: sanitizeEnv(process.env),
      stdio: ['ignore', 'inherit', 'inherit'],
      windowsHide: true,
      shell: process.platform === 'win32' && /\.(cmd|bat)$/i.test(command)
    });
    child.on('error', reject);
    child.on('close', resolve);
  });
}

function parseArgs(raw) {
  const parsed = {};
  for (let index = 0; index < raw.length; index += 1) {
    const arg = raw[index];
    if (arg === '--no-build') parsed.build = false;
    else if (arg === '--ticks') parsed.ticks = readPositiveInteger(raw[++index], '--ticks');
    else if (arg === '--no-wait') parsed.wait = false;
    else if (arg === '--max-sleep-ms') parsed.maxSleepMs = readPositiveInteger(raw[++index], '--max-sleep-ms');
    else if (arg === '--session') parsed.session = raw[++index];
    else if (arg === '--store') parsed.store = raw[++index];
    else if (arg === '--jsonl') parsed.jsonl = raw[++index];
    else if (arg === '--markdown') parsed.markdown = raw[++index];
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

function sanitizeEnv(env) {
  return Object.fromEntries(Object.entries(env).filter((entry) => typeof entry[1] === 'string'));
}

function formatMs(value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return 'n/a';
  if (value < 1000) return `${value}ms`;
  return `${(value / 1000).toFixed(2)}s`;
}

function helpText() {
  return `Atlas ambient Android/OpenClaw loop smoke\n\nUsage:\n  npm run demo:smoke-ambient-android -- [--ticks 3] [--max-sleep-ms 30000]\n                                      [--no-build] [--no-wait]\n                                      [--session id] [--store path]\n                                      [--image-worker auto|true|false]\n\nRuns the live Android/OpenClaw ambient loop with the example config. Defaults to waiting between ticks using Atlas cadence, capped at 30s.`;
}
