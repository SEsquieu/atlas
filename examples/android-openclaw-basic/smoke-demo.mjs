#!/usr/bin/env node
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const args = parseArgs(process.argv.slice(2));
const prompt = (args.prompt ?? args._.join(' ')) || process.env.ATLAS_LIVE_TEXT || 'What am I looking at?';
const shouldBuild = args.build !== false;
const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const liveDemoPath = path.join(repoRoot, 'examples', 'android-openclaw-basic', 'live-demo.mjs');
const ambientDemoPath = path.join(repoRoot, 'examples', 'android-openclaw-basic', 'ambient-demo.mjs');
const startedAt = Date.now();
const results = [];

try {
  console.log('Atlas Android/OpenClaw smoke demo');
  console.log(`Prompt: ${prompt}`);
  console.log(`Build: ${shouldBuild ? 'yes' : 'skipped'}`);
  console.log('');

  if (shouldBuild) {
    results.push(await runStep('build', [npmCommand, ['run', 'build']]));
  }

  results.push(await runStep('live fast ask', [process.execPath, [liveDemoPath, '--fast', prompt]]));
  results.push(await runStep('ambient heartbeat + cached ask', [process.execPath, [ambientDemoPath, prompt]]));

  console.log('');
  console.log('Smoke summary:');
  console.log(`- overall: passed in ${formatMs(Date.now() - startedAt)}`);
  for (const result of results) {
    const summary = summarizeOutput(result.output);
    console.log(`- ${result.name}: ${formatMs(result.durationMs)}`);
    if (typeof summary.measuredMs === 'number') {
      console.log(`  - measured Atlas path: ${formatMs(summary.measuredMs)}`);
      console.log(`  - harness/setup overhead: ${formatMs(Math.max(0, result.durationMs - summary.measuredMs))}`);
    }
    for (const line of summary.lines) console.log(`  - ${line}`);
  }
} catch (error) {
  console.error('');
  console.error('Smoke summary: failed');
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}

async function runStep(name, [command, stepArgs]) {
  console.log(`==> ${name}`);
  const started = Date.now();
  const result = await run(command, stepArgs);
  const durationMs = Date.now() - started;
  if (result.code !== 0) {
    throw new Error(`${name} failed with code ${result.code}`);
  }
  console.log(`==> ${name} passed in ${formatMs(durationMs)}`);
  console.log('');
  return { name, durationMs, output: `${result.stdout}\n${result.stderr}` };
}

async function run(command, stepArgs) {
  return await new Promise((resolve, reject) => {
    const child = spawn(command, stepArgs, {
      cwd: repoRoot,
      env: sanitizeEnv(process.env),
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
      shell: process.platform === 'win32' && /\.(cmd|bat)$/i.test(command)
    });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
      process.stdout.write(chunk);
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
      process.stderr.write(chunk);
    });
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
  });
}

function sanitizeEnv(env) {
  return Object.fromEntries(Object.entries(env).filter((entry) => typeof entry[1] === 'string'));
}

function summarizeOutput(output) {
  const lines = [];
  const measuredMs = measuredAtlasPathMs(output);
  const patterns = [
    /^- Total user turn: .+$/m,
    /^- Atlas capture round trip: .+$/m,
    /^- Bridge total: .+$/m,
    /^- Provider round trip: .+$/m,
    /^- heartbeat wall time: .+$/m,
    /^- ambient bridge: .+$/m,
    /^- ask wall time: .+$/m,
    /^- refreshed during ask: .+$/m,
    /^- user turn timing: .+$/m,
    /^Latest observation: .+$/m,
    /^Media: .+$/m
  ];
  for (const pattern of patterns) {
    const match = output.match(pattern);
    if (match) lines.push(match[0].replace(/^- /, ''));
  }
  return { lines, measuredMs };
}

function measuredAtlasPathMs(output) {
  const totalUserTurn = readDuration(output, /^- Total user turn: (.+)$/m);
  if (typeof totalUserTurn === 'number') return totalUserTurn;
  const heartbeat = readDuration(output, /^- heartbeat wall time: (.+)$/m);
  const ask = readDuration(output, /^- ask wall time: (.+)$/m);
  if (typeof heartbeat === 'number' || typeof ask === 'number') return (heartbeat ?? 0) + (ask ?? 0);
  return undefined;
}

function readDuration(output, pattern) {
  const match = output.match(pattern);
  if (!match) return undefined;
  return parseDuration(match[1]);
}

function parseDuration(raw) {
  const trimmed = raw.trim();
  const match = trimmed.match(/^(\d+(?:\.\d+)?)(ms|s)$/);
  if (!match) return undefined;
  const value = Number(match[1]);
  if (!Number.isFinite(value)) return undefined;
  return match[2] === 's' ? Math.round(value * 1000) : value;
}

function parseArgs(values) {
  const parsed = { _: [] };
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === '--no-build') {
      parsed.build = false;
      continue;
    }
    if (value === '--prompt') {
      parsed.prompt = values[index + 1] ?? '';
      index += 1;
      continue;
    }
    if (value.startsWith('--prompt=')) {
      parsed.prompt = value.slice('--prompt='.length);
      continue;
    }
    parsed._.push(value);
  }
  return parsed;
}

function formatMs(value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return 'n/a';
  if (value < 1000) return `${value}ms`;
  return `${(value / 1000).toFixed(2)}s`;
}
