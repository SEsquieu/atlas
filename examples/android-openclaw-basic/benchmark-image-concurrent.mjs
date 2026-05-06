#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { stat } from 'node:fs/promises';
import path from 'node:path';

const imagePath = process.argv[2];
const model = process.argv[3] ?? process.env.ATLAS_BENCH_IMAGE_MODEL ?? 'openai-codex/gpt-5.5';
const concurrency = Number(process.argv[4] ?? process.env.ATLAS_BENCH_CONCURRENCY ?? '2');
const openclawMjs = process.env.OPENCLAW_MJS ?? 'C:\\Users\\16096\\AppData\\Roaming\\npm\\node_modules\\openclaw\\openclaw.mjs';

if (!imagePath) {
  console.error('Usage: node examples/android-openclaw-basic/benchmark-image-concurrent.mjs <imagePath> [model] [concurrency]');
  process.exit(1);
}

const image = path.resolve(imagePath);
const imageStat = await stat(image);
const suiteStartedAt = Date.now();
console.log(JSON.stringify({ kind: 'concurrent.start', image, imageBytes: imageStat.size, model, concurrency, openclawMjs, startedAt: new Date(suiteStartedAt).toISOString() }, null, 2));

const samples = await Promise.all(
  Array.from({ length: concurrency }, async (_, index) => {
    const startedAt = Date.now();
    const result = await run(process.execPath, [openclawMjs, 'infer', 'image', 'describe', '--file', image, '--model', model, '--json'], { timeoutMs: 240000 });
    const endedAt = Date.now();
    const parsed = parseImageOutput(result.stdout);
    const sample = {
      index: index + 1,
      ok: result.code === 0,
      code: result.code,
      startedOffsetMs: startedAt - suiteStartedAt,
      endedOffsetMs: endedAt - suiteStartedAt,
      durationMs: endedAt - startedAt,
      stdoutBytes: Buffer.byteLength(result.stdout),
      stderrBytes: Buffer.byteLength(result.stderr),
      stderrPreview: result.stderr.trim().slice(0, 240) || undefined,
      textChars: parsed.text?.length ?? 0,
      textPreview: parsed.text?.slice(0, 180)
    };
    console.log(JSON.stringify({ kind: 'concurrent.sample', ...sample }, null, 2));
    return sample;
  })
);

const suiteEndedAt = Date.now();
console.log(JSON.stringify({
  kind: 'concurrent.done',
  image,
  imageBytes: imageStat.size,
  model,
  concurrency,
  wallMs: suiteEndedAt - suiteStartedAt,
  overlapInference: inferOverlap(samples),
  statsMs: stats(samples.map((sample) => sample.durationMs)),
  samples: samples.sort((a, b) => a.index - b.index)
}, null, 2));

function inferOverlap(samples) {
  if (samples.length < 2) return 'n/a';
  const maxDuration = Math.max(...samples.map((sample) => sample.durationMs));
  const sumDuration = samples.reduce((sum, sample) => sum + sample.durationMs, 0);
  const wallSpan = Math.max(...samples.map((sample) => sample.endedOffsetMs)) - Math.min(...samples.map((sample) => sample.startedOffsetMs));
  if (wallSpan < sumDuration * 0.65 && wallSpan <= maxDuration * 1.25) return 'overlapped';
  if (wallSpan > sumDuration * 0.85) return 'mostly-serialized';
  return 'partial-overlap';
}

function parseImageOutput(stdout) {
  try {
    const parsed = JSON.parse(stdout.trim());
    const text = parsed?.outputs?.[0]?.text;
    return { parsed, text: typeof text === 'string' ? text : undefined };
  } catch {
    return { parsed: undefined, text: undefined };
  }
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
