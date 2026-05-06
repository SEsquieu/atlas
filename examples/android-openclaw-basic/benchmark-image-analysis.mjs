#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { stat } from 'node:fs/promises';
import path from 'node:path';

const imagePath = process.argv[2];
const model = process.argv[3] ?? process.env.ATLAS_BENCH_IMAGE_MODEL ?? 'openai-codex/gpt-5.5';
const runs = Number(process.argv[4] ?? process.env.ATLAS_BENCH_RUNS ?? '5');
const openclawMjs = process.env.OPENCLAW_MJS ?? 'C:\\Users\\16096\\AppData\\Roaming\\npm\\node_modules\\openclaw\\openclaw.mjs';

if (!imagePath) {
  console.error('Usage: npm run bench:image-openclaw -- <imagePath> [model] [runs]');
  process.exit(1);
}

const image = path.resolve(imagePath);
const imageStat = await stat(image);
console.log(JSON.stringify({ kind: 'benchmark.start', image, imageBytes: imageStat.size, model, runs, openclawMjs }, null, 2));

const startupSamples = [];
for (let index = 0; index < Math.min(3, runs); index += 1) {
  startupSamples.push(await timedRun(process.execPath, [openclawMjs, '--version'], { timeoutMs: 30000 }));
}
console.log(JSON.stringify({
  kind: 'startup.baseline',
  samples: startupSamples.map((sample) => summarizeRun(sample)),
  stats: stats(startupSamples.map((sample) => sample.durationMs))
}, null, 2));

const samples = [];
for (let index = 0; index < runs; index += 1) {
  const sample = await timedRun(process.execPath, [openclawMjs, 'infer', 'image', 'describe', '--file', image, '--model', model, '--json'], { timeoutMs: 240000 });
  const parsed = parseImageOutput(sample.stdout);
  const summary = { index: index + 1, ...summarizeRun(sample), textChars: parsed.text?.length ?? 0, textPreview: parsed.text?.slice(0, 180) };
  samples.push({ ...sample, parsed, summary });
  console.log(JSON.stringify({ kind: 'image.sample', ...summary }, null, 2));
}

console.log(JSON.stringify({
  kind: 'benchmark.done',
  image,
  imageBytes: imageStat.size,
  model,
  startupStatsMs: stats(startupSamples.map((sample) => sample.durationMs)),
  imageStatsMs: stats(samples.map((sample) => sample.durationMs)),
  successfulImageStatsMs: stats(samples.filter((sample) => sample.code === 0).map((sample) => sample.durationMs)),
  samples: samples.map((sample) => sample.summary)
}, null, 2));

function parseImageOutput(stdout) {
  try {
    const parsed = JSON.parse(stdout.trim());
    const text = parsed?.outputs?.[0]?.text;
    return { parsed, text: typeof text === 'string' ? text : undefined };
  } catch {
    return { parsed: undefined, text: undefined };
  }
}

function summarizeRun(sample) {
  return {
    ok: sample.code === 0,
    code: sample.code,
    durationMs: sample.durationMs,
    stdoutBytes: Buffer.byteLength(sample.stdout),
    stderrBytes: Buffer.byteLength(sample.stderr),
    stderrPreview: sample.stderr.trim().slice(0, 240) || undefined
  };
}

function stats(values) {
  const sorted = [...values].sort((a, b) => a - b);
  if (sorted.length === 0) return undefined;
  const sum = sorted.reduce((total, value) => total + value, 0);
  return {
    count: sorted.length,
    min: sorted[0],
    max: sorted.at(-1),
    mean: Math.round(sum / sorted.length),
    median: sorted[Math.floor(sorted.length / 2)]
  };
}

async function timedRun(command, args, options = {}) {
  const startedAt = Date.now();
  const result = await run(command, args, options);
  return { ...result, durationMs: Date.now() - startedAt };
}

async function run(command, args, options = {}) {
  return await new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    const timeout = options.timeoutMs
      ? setTimeout(() => {
          child.kill();
          reject(new Error(`${command} ${args.join(' ')} timed out after ${options.timeoutMs}ms`));
        }, options.timeoutMs)
      : undefined;
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => (stdout += chunk));
    child.stderr.on('data', (chunk) => (stderr += chunk));
    child.on('error', (error) => {
      if (timeout) clearTimeout(timeout);
      reject(error);
    });
    child.on('close', (code) => {
      if (timeout) clearTimeout(timeout);
      resolve({ code, stdout, stderr });
    });
  });
}
