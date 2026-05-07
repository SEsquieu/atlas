#!/usr/bin/env node
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { deflateSync } from 'node:zlib';

const repoRoot = fileURLToPath(new URL('../..', import.meta.url));
const args = parseArgs(process.argv.slice(2));
const host = firstString(args.host, process.env.ATLAS_OPENCLAW_IMAGE_WORKER_HOST) ?? '127.0.0.1';
const port = readNumber(firstString(args.port, process.env.ATLAS_OPENCLAW_IMAGE_WORKER_PORT)) ?? 0;
const defaultModel = firstString(args.model, process.env.ATLAS_OPENCLAW_IMAGE_WORKER_MODEL, process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_MODEL) ?? 'openai-codex/gpt-5.5';
const defaultPrompt = firstString(process.env.ATLAS_OPENCLAW_IMAGE_WORKER_PROMPT) ?? 'Describe the image.';
const defaultTimeoutMs = readNumber(process.env.ATLAS_OPENCLAW_IMAGE_WORKER_TIMEOUT_MS) ?? 180000;
const prewarmEnabled = parseBoolean(firstString(args.prewarm, process.env.ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM), false);
const prewarmImage = firstString(args.prewarmImage, process.env.ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM_IMAGE);
const prewarmModel = firstString(args.prewarmModel, process.env.ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM_MODEL) ?? defaultModel;

let runtime;
let initPromise;
let queue = Promise.resolve();
let requestCount = 0;
let lastError;

try {
  logProgress('initializing OpenClaw image runtime');
  await initializeRuntime();
  logProgress('OpenClaw image runtime initialized');
  if (prewarmEnabled) {
    logProgress('prewarming OpenClaw image runtime');
    await prewarmRuntime();
    logProgress('OpenClaw image runtime prewarm complete');
  }
  const server = http.createServer(handleRequest);
  await listen(server, port, host);
  const address = server.address();
  const actualPort = typeof address === 'object' && address ? address.port : port;
  const url = `http://${host}:${actualPort}`;
  process.stdout.write(`${JSON.stringify({ kind: 'atlas.openclaw-image-worker.ready', url, pid: process.pid, prewarmed: prewarmEnabled })}\n`);

  const shutdown = () => server.close(() => process.exit(0));
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
} catch (error) {
  process.stderr.write(`${formatError(error)}\n`);
  process.exit(1);
}

async function handleRequest(req, res) {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      await initializeRuntime();
      sendJson(res, 200, { ok: true, requestCount, lastError });
      return;
    }

    if (req.method === 'POST' && req.url === '/describe') {
      const body = await readJsonBody(req);
      const result = await enqueue(() => describe(body));
      sendJson(res, 200, result);
      return;
    }

    sendJson(res, 404, { ok: false, error: 'Not found.' });
  } catch (error) {
    lastError = formatError(error);
    sendJson(res, 500, { ok: false, error: lastError });
  }
}

async function enqueue(fn) {
  const run = queue.catch(() => undefined).then(fn);
  queue = run.then(() => undefined, () => undefined);
  return await run;
}

async function initializeRuntime() {
  initPromise ??= (async () => {
    const dist = resolveOpenClawDistDir();
    const mod = (name) => import(pathToFileURL(path.join(dist, name)).href);
    const [{ a: loadConfig }, { t: resolveOpenClawAgentDir }, { n: ensureOpenClawModelsJson }, { n: describeImageFileWithModel }] = await Promise.all([
      mod('io-Z7dwPjtk.js'),
      mod('agent-paths-EhZr8Ip8.js'),
      mod('models-config-Dj7ZCZ0C.js'),
      mod('runtime-B46agMNN.js')
    ]);
    const cfg = loadConfig();
    const agentDir = resolveOpenClawAgentDir();
    await ensureOpenClawModelsJson(cfg, agentDir);
    runtime = { cfg, agentDir, describeImageFileWithModel };
    return runtime;
  })();
  return await initPromise;
}

async function describe(body = {}) {
  const ready = await initializeRuntime();
  const imagePath = firstString(body.imagePath, body.filePath);
  if (!imagePath) throw new Error('imagePath is required.');
  const [provider, model] = splitModel(firstString(body.model) ?? defaultModel);
  const prompt = firstString(body.prompt) ?? defaultPrompt;
  const timeoutMs = readNumber(body.timeoutMs) ?? defaultTimeoutMs;
  const startedAt = Date.now();
  const result = await ready.describeImageFileWithModel({
    filePath: path.resolve(imagePath),
    cfg: ready.cfg,
    agentDir: ready.agentDir,
    provider,
    model,
    prompt,
    timeoutMs
  });
  requestCount += 1;
  return {
    ok: true,
    text: String(result.text ?? '').trim(),
    model: result.model,
    provider,
    durationMs: Date.now() - startedAt
  };
}

async function prewarmRuntime() {
  const imagePath = prewarmImage ? path.resolve(prewarmImage) : await ensureTinyPrewarmPng();
  try {
    await describe({ imagePath, model: prewarmModel, prompt: 'Briefly describe this calibration image.', timeoutMs: defaultTimeoutMs });
  } catch (error) {
    // Prewarm is a latency optimization, not a hard startup dependency.
    lastError = `prewarm failed: ${formatError(error)}`;
    process.stderr.write(`${lastError}\n`);
  }
}

async function ensureTinyPrewarmPng() {
  const outputPath = path.join(repoRoot, '.atlas-cache', 'images', 'openclaw-worker-prewarm.png');
  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, createSolidPng(32, 32, [24, 96, 192]));
  return outputPath;
}

function createSolidPng(width, height, [red, green, blue]) {
  const scanlineLength = 1 + width * 3;
  const raw = Buffer.alloc(scanlineLength * height);
  for (let y = 0; y < height; y += 1) {
    const row = y * scanlineLength;
    raw[row] = 0; // PNG filter type: none.
    for (let x = 0; x < width; x += 1) {
      const offset = row + 1 + x * 3;
      raw[offset] = red;
      raw[offset + 1] = green;
      raw[offset + 2] = blue;
    }
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // truecolor RGB
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0))
  ]);
}

function pngChunk(type, data) {
  const typeBuffer = Buffer.from(type, 'ascii');
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 0);
  return Buffer.concat([length, typeBuffer, data, crc]);
}

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function resolveOpenClawDistDir() {
  const explicit = firstString(process.env.OPENCLAW_DIST_DIR, process.env.ATLAS_OPENCLAW_DIST_DIR);
  if (explicit) return path.resolve(explicit);
  const openclawMjs = firstString(process.env.OPENCLAW_MJS, process.env.ATLAS_OPENCLAW_MJS);
  if (openclawMjs) return path.join(path.dirname(path.resolve(openclawMjs)), 'dist');
  if (process.env.APPDATA) return path.join(process.env.APPDATA, 'npm', 'node_modules', 'openclaw', 'dist');
  return path.join(process.env.USERPROFILE ?? process.cwd(), 'AppData', 'Roaming', 'npm', 'node_modules', 'openclaw', 'dist');
}

function listen(server, requestedPort, requestedHost) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(requestedPort, requestedHost, () => {
      server.off('error', reject);
      resolve();
    });
  });
}

async function readJsonBody(req) {
  let raw = '';
  req.setEncoding('utf8');
  for await (const chunk of req) raw += chunk;
  return raw.trim() ? JSON.parse(raw) : {};
}

function sendJson(res, statusCode, payload) {
  const body = `${JSON.stringify(payload)}\n`;
  res.writeHead(statusCode, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) });
  res.end(body);
}

function splitModel(modelRef) {
  const slash = modelRef.indexOf('/');
  if (slash === -1) return ['openai-codex', modelRef];
  return [modelRef.slice(0, slash), modelRef.slice(slash + 1)];
}

function parseArgs(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (!value.startsWith('--')) continue;
    const key = value.slice(2).replace(/-([a-z])/g, (_, char) => char.toUpperCase());
    const next = values[index + 1];
    if (!next || next.startsWith('--')) parsed[key] = 'true';
    else {
      parsed[key] = next;
      index += 1;
    }
  }
  return parsed;
}

function parseBoolean(value, fallback) {
  if (typeof value !== 'string' || !value.trim()) return fallback;
  if (/^(1|true|yes|on)$/i.test(value)) return true;
  if (/^(0|false|no|off)$/i.test(value)) return false;
  return fallback;
}

function readNumber(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value !== 'string' || !value.trim()) return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function firstString(...values) {
  return values.find((value) => typeof value === 'string' && value.length > 0);
}

function logProgress(message) {
  process.stderr.write(`[atlas-openclaw-image-worker] ${message}\n`);
}

function formatError(error) {
  return error instanceof Error ? error.message : String(error);
}
