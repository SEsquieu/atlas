#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { appendFile, mkdir } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const cliPath = path.join(repoRoot, 'packages', 'atlas-cli', 'dist', 'index.js');
const args = parseArgs(process.argv.slice(2));
const sessionId = args.session ?? process.env.ATLAS_AMBIENT_SESSION_ID ?? 'ambient-loop-fake';
const storeRoot = args.store ?? process.env.ATLAS_STORE ?? path.join(os.tmpdir(), `atlas-ambient-loop-${Date.now()}`);
const ticks = args.ticks ?? 3;
const shouldWait = args.wait === true;
const maxSleepMs = args.maxSleepMs ?? 5_000;
const logDir = path.join(storeRoot, sessionId);
const jsonlPath = args.jsonl ?? path.join(logDir, 'ambient-loop.jsonl');
const markdownPath = args.markdown ?? path.join(logDir, 'ambient-loop.md');

let stopped = false;
process.on('SIGINT', () => {
  stopped = true;
  console.log('\nStopping ambient loop after current tick...');
});

try {
  console.log('Atlas ambient loop runner');
  console.log(`Session: ${sessionId}`);
  console.log(`Store: ${storeRoot}`);
  console.log(`Ticks: ${ticks}`);
  console.log(`Wait: ${shouldWait ? `yes (max ${maxSleepMs}ms)` : 'no'}`);
  console.log(`JSONL: ${jsonlPath}`);
  console.log(`Markdown: ${markdownPath}`);
  if (args.config) console.log(`Config: ${args.config}`);
  else console.log('Config: none (uses built-in fake adapters; no phone camera)');
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

  console.log('');
  console.log(`Wrote loop summaries to:\n- ${jsonlPath}\n- ${markdownPath}`);
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
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
    timing: inspection.timing,
    mediaRef: latest?.mediaRef
  };
}

async function appendLoopLogs(entry) {
  await appendFile(jsonlPath, `${JSON.stringify(entry)}\n`, 'utf8');
  await appendFile(markdownPath, formatMarkdownEntry(entry), 'utf8');
}

function formatMarkdownEntry(entry) {
  const lines = [
    `## Tick ${entry.tick} — ${entry.at}`,
    '',
    `- cadence: ${entry.cadence.mode ?? 'unknown'} next=${formatMs(entry.cadence.nextDelayMs)} (${entry.cadence.reason ?? 'no reason'})`,
    `- freshness: age=${formatMs(entry.freshness?.contextAgeMs)} staleAfter=${formatMs(entry.freshness?.staleAfterMs)} multiplier=${entry.freshness?.multiplier ?? '?'} stale=${entry.freshness?.stale ?? '?'}`,
    entry.freshness?.signals?.length ? `- freshness signals: ${entry.freshness.signals.join(', ')}` : undefined,
    `- capture: ${entry.captured ? `yes (${entry.observationId})` : 'no'}`,
    `- decision: ${entry.decision.reason ?? 'n/a'}`,
    `- significance: ${entry.significance ? `${entry.significance.level} score=${formatScore(entry.significance.score)} provider=${entry.significance.shouldCallProvider} notify=${entry.significance.shouldNotifyUser}` : 'not assessed'}`,
    `- wall time: ${formatMs(entry.wallMs)}`,
    entry.mediaRef ? `- media: ${entry.mediaRef}` : undefined,
    '',
    entry.summary ? `summary: ${entry.summary}` : 'summary: (none)',
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
      `capture=${entry.captured ? 'yes' : 'no'}`,
      `significance=${entry.significance?.level ?? 'n/a'}`,
      `wall=${formatMs(entry.wallMs)}`,
      entry.summary ? `summary=${truncate(entry.summary, 120)}` : 'summary=(none)'
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

function helpText() {
  return `Atlas ambient loop runner\n\nUsage:\n  node examples/ambient-loop.mjs [--ticks 3] [--wait] [--max-sleep-ms 5000]\n                                 [--session id] [--store path] [--config path]\n                                 [--jsonl path] [--markdown path]\n\nDefault mode uses built-in fake adapters and does not invoke the phone camera. Passing a live Android config will invoke whatever device adapter that config selects.`;
}
