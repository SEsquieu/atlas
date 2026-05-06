#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const IMAGE_EXTENSIONS = new Set(['.jpg', '.jpeg', '.png', '.webp']);

try {
  const options = await readJsonInput('ATLAS_ANDROID_BRIDGE_OPTIONS');
  const result = await captureWithOpenClaw(options);
  process.stdout.write(`${JSON.stringify(result)}\n`);
} catch (error) {
  process.stderr.write(`${formatError(error)}\n`);
  process.exitCode = 1;
}

async function captureWithOpenClaw(options) {
  const startedAt = Date.now();
  const node = firstString(options.node, process.env.ATLAS_ANDROID_BRIDGE_NODE) ?? 'paired-android-node';
  const facing = firstString(options.facing, process.env.ATLAS_ANDROID_BRIDGE_FACING) ?? 'back';
  const openclaw = resolveOpenClawInvocation(firstString(process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN));
  const tempDir = firstString(process.env.ATLAS_ANDROID_BRIDGE_TEMP_DIR) ?? path.join(os.tmpdir(), 'openclaw');
  const imagesDir = path.resolve(firstString(process.env.ATLAS_ANDROID_BRIDGE_IMAGES_DIR) ?? path.join('.atlas-cache', 'images'));
  const latestFileName = firstString(process.env.ATLAS_ANDROID_BRIDGE_LATEST_FILE_NAME) ?? 'latest';
  const stage = process.env.ATLAS_ANDROID_BRIDGE_STAGE !== 'false';

  const helperArgs = ['nodes', 'camera', 'snap', '--node', node, '--facing', facing];
  appendOptionalArg(helperArgs, '--max-width', options.maxWidth);
  appendOptionalArg(helperArgs, '--quality', normalizeQuality(options.quality));
  appendOptionalArg(helperArgs, '--delay-ms', options.delayMs);

  const helper = await runCommand(openclaw.command, [...openclaw.args, ...helperArgs], { timeoutMs: readNumberEnv('ATLAS_ANDROID_BRIDGE_CAPTURE_TIMEOUT_MS') ?? 30000 });
  const captureEndedAt = Date.now();
  const helperMedia = await parseMediaPaths(helper.stdout);
  const selectedSource = await resolveFreshSourceImage({ helperMedia, tempDir, startedAt });
  const observedAtMs = Number.isFinite(selectedSource.mtimeMs) ? selectedSource.mtimeMs : captureEndedAt;

  const staged = stage
    ? await stageImageIntoWorkspace({ sourcePath: selectedSource.normalizedPath, imagesDir, facing, latestFileName })
    : { archivePath: selectedSource.normalizedPath, latestPath: selectedSource.normalizedPath };
  const stageEndedAt = Date.now();

  const analyze = options.analyze !== false;
  const requestedMode = firstString(options.analysisMode, process.env.ATLAS_ANDROID_BRIDGE_ANALYSIS_MODE) ?? 'none';
  const analysisMode = analyze ? requestedMode : 'none';
  let analysisState = analysisMode === 'none' ? 'skipped' : 'not-run';
  let analysisText = '';

  const analysisStartedAt = Date.now();

  if (analysisMode === 'ollama') {
    try {
      analysisText = await describeImageWithOllama({
        imagePath: staged.archivePath,
        baseUrl: firstString(process.env.ATLAS_ANDROID_BRIDGE_OLLAMA_BASE_URL) ?? 'http://127.0.0.1:11434',
        model: firstString(process.env.ATLAS_ANDROID_BRIDGE_OLLAMA_MODEL) ?? 'qwen3.5:4b',
        prompt:
          firstString(options.prompt, process.env.ATLAS_ANDROID_BRIDGE_OLLAMA_PROMPT) ??
          'Describe what is visible in this image in plain language. Be direct and concrete.'
      });
      analysisState = analysisText ? 'ok' : 'empty';
    } catch (error) {
      analysisState = `error:${formatError(error)}`;
      analysisText = `Image captured and staged, but Ollama analysis failed: ${formatError(error)}`;
    }
  } else if (analysisMode === 'openclaw') {
    try {
      analysisText = await describeImageWithOpenClaw({
        imagePath: staged.archivePath,
        model: firstString(process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_MODEL) ?? 'openai-codex/gpt-5.5',
        timeoutMs: readNumberEnv('ATLAS_ANDROID_BRIDGE_ANALYSIS_TIMEOUT_MS') ?? 180000
      });
      analysisState = analysisText ? 'ok' : 'empty';
    } catch (error) {
      analysisState = `error:${formatError(error)}`;
      analysisText = `Image captured and staged, but OpenClaw image analysis failed: ${formatError(error)}`;
    }
  }

  const analysisEndedAt = Date.now();
  return {
    ok: true,
    node,
    facing,
    sourceTempPath: selectedSource.normalizedPath,
    workspaceImagePath: staged.archivePath,
    workspaceLatestPath: staged.latestPath,
    mediaRef: staged.archivePath,
    capturedAt: new Date(observedAtMs).toISOString(),
    observedAt: new Date(observedAtMs).toISOString(),
    availableAt: new Date(analysisEndedAt).toISOString(),
    analyzed: analysisMode !== 'none',
    analysisMode,
    analysisState,
    analysisText,
    summary: analysisText || undefined,
    timings: {
      totalMs: analysisEndedAt - startedAt,
      captureMs: captureEndedAt - startedAt,
      stageMs: stageEndedAt - captureEndedAt,
      analysisMs: analysisEndedAt - analysisStartedAt
    },
    timestamps: {
      startedAt: new Date(startedAt).toISOString(),
      captureCompletedAt: new Date(captureEndedAt).toISOString(),
      sourceImageModifiedAt: new Date(observedAtMs).toISOString(),
      stagedAt: new Date(stageEndedAt).toISOString(),
      analysisStartedAt: new Date(analysisStartedAt).toISOString(),
      analysisCompletedAt: new Date(analysisEndedAt).toISOString(),
      availableAt: new Date(analysisEndedAt).toISOString()
    }
  };
}

async function readJsonInput(envKey) {
  const envValue = process.env[envKey];
  if (envValue?.trim()) return JSON.parse(envValue);

  if (process.stdin.isTTY) return {};
  let stdin = '';
  process.stdin.setEncoding('utf8');
  for await (const chunk of process.stdin) stdin += chunk;
  return stdin.trim() ? JSON.parse(stdin) : {};
}

function appendOptionalArg(args, flag, value) {
  if (value === undefined || value === null || value === '') return;
  args.push(flag, String(value));
}

function normalizeQuality(value) {
  if (typeof value === 'number') return value;
  if (value === 'low') return 0.45;
  if (value === 'medium') return 0.7;
  if (value === 'high') return 0.92;
  return undefined;
}

async function parseMediaPaths(stdout) {
  const lines = stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.startsWith('MEDIA:'));

  const results = [];
  for (const line of lines) {
    const rawPath = line.slice('MEDIA:'.length).trim();
    const normalizedPath = stripWrappingQuotes(rawPath);
    if (!normalizedPath) continue;
    try {
      const stat = await fs.stat(normalizedPath);
      if (stat.isFile()) results.push({ rawPath, normalizedPath, mtimeMs: stat.mtimeMs });
    } catch {
      // Ignore stale MEDIA lines and fall back to temp-dir scan.
    }
  }
  return results.sort((a, b) => b.mtimeMs - a.mtimeMs);
}

async function resolveFreshSourceImage({ helperMedia, tempDir, startedAt }) {
  const freshHelperHit = helperMedia.find((entry) => entry.mtimeMs >= startedAt - 5000);
  if (freshHelperHit) return freshHelperHit;

  const tempEntries = await listCandidateImages(tempDir);
  const freshTempHit = tempEntries.find((entry) => entry.mtimeMs >= startedAt - 5000);
  if (freshTempHit) return freshTempHit;

  if (helperMedia[0]) return helperMedia[0];
  if (tempEntries[0]) return tempEntries[0];

  throw new Error(`No fresh image was found after capture. Checked MEDIA lines and temp directory: ${tempDir}`);
}

async function listCandidateImages(dir) {
  let entries;
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch (error) {
    if (error?.code === 'ENOENT') return [];
    throw error;
  }

  const images = [];
  for (const entry of entries) {
    if (!entry.isFile()) continue;
    const fullPath = path.join(dir, entry.name);
    if (!IMAGE_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) continue;
    const stat = await fs.stat(fullPath);
    images.push({ rawPath: fullPath, normalizedPath: fullPath, mtimeMs: stat.mtimeMs });
  }
  return images.sort((a, b) => b.mtimeMs - a.mtimeMs);
}

async function stageImageIntoWorkspace({ sourcePath, imagesDir, facing, latestFileName }) {
  await fs.mkdir(imagesDir, { recursive: true });
  const ext = path.extname(sourcePath) || '.jpg';
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const archivePath = path.join(imagesDir, `${facing}-capture-${stamp}-${randomUUID()}${ext}`);
  const latestPath = path.join(imagesDir, `${latestFileName}-${facing}${ext}`);
  await fs.copyFile(sourcePath, archivePath);
  await fs.copyFile(sourcePath, latestPath);
  return { archivePath, latestPath };
}

async function describeImageWithOllama({ imagePath, baseUrl, model, prompt }) {
  const imageBuffer = await fs.readFile(imagePath);
  const response = await fetch(new URL('/api/generate', baseUrl), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ model, prompt, images: [imageBuffer.toString('base64')], stream: false })
  });

  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(`Ollama request failed (${response.status}): ${body || response.statusText}`);
  }

  const json = await response.json();
  if (json.error) throw new Error(json.error);
  return String(json.response ?? '').trim();
}

async function describeImageWithOpenClaw({ imagePath, model, timeoutMs }) {
  const openclaw = resolveOpenClawInvocation(firstString(process.env.ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN));
  const args = ['infer', 'image', 'describe', '--file', imagePath, '--model', model, '--json'];
  const output = await runCommand(openclaw.command, [...openclaw.args, ...args], { timeoutMs });
  const parsed = JSON.parse(output.stdout.trim());
  const text = parsed?.outputs?.[0]?.text;
  if (typeof text !== 'string' || text.trim().length === 0) {
    throw new Error('OpenClaw image describe returned no text.');
  }
  return text.trim();
}

async function runCommand(command, args, options = {}) {
  return await new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true, shell: shouldUseShell(command) });
    const timeout = options.timeoutMs
      ? setTimeout(() => {
          child.kill();
          reject(new Error(`${command} timed out after ${options.timeoutMs}ms.`));
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
      if (code !== 0) {
        reject(new Error(`${command} ${args.join(' ')} exited with code ${code}.${stderr ? ` stderr: ${stderr.trim()}` : ''}`));
      } else {
        resolve({ stdout, stderr });
      }
    });
  });
}

function resolveOpenClawInvocation(configuredBin) {
  if (process.platform === 'win32') {
    const npmDir = configuredBin && /\.(cmd|bat)$/i.test(configuredBin)
      ? path.dirname(configuredBin)
      : path.join(process.env.APPDATA ?? '', 'npm');
    return {
      command: process.execPath,
      args: [path.join(npmDir, 'node_modules', 'openclaw', 'openclaw.mjs')]
    };
  }
  return { command: configuredBin ?? 'openclaw', args: [] };
}

function shouldUseShell(command) {
  return process.platform === 'win32' && /\.(cmd|bat)$/i.test(command);
}

function firstString(...values) {
  return values.find((value) => typeof value === 'string' && value.length > 0);
}

function readNumberEnv(key) {
  const value = Number(process.env[key]);
  return Number.isFinite(value) && value > 0 ? value : undefined;
}

function stripWrappingQuotes(value) {
  return value.replace(/^["']+|["']+$/g, '');
}

function formatError(error) {
  return error instanceof Error ? error.message : String(error);
}
