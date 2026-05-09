#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const atlasRoot = fileURLToPath(new URL('../..', import.meta.url));
const args = process.argv.slice(2);
const text = readFlag('--text') ?? args.find((arg) => !arg.startsWith('--')) ?? 'Atlas TTS smoke test. If you hear this, the phone speaker path is alive.';
const nodeName = readFlag('--node') ?? process.env.ATLAS_ANDROID_BRIDGE_NODE ?? 'Galaxy S22 Ultra';
const timeoutMs = readFlag('--timeout-ms') ?? process.env.ATLAS_ANDROID_BRIDGE_SPEAK_TIMEOUT_MS ?? '15000';

const child = spawn(process.execPath, ['examples/android-openclaw-basic/bridge-wrapper.mjs'], {
  cwd: atlasRoot,
  env: {
    ...process.env,
    ATLAS_ANDROID_BRIDGE_NODE: nodeName,
    ATLAS_ANDROID_BRIDGE_SPEAK_TIMEOUT_MS: timeoutMs,
    ATLAS_ANDROID_BRIDGE_SPEAK: JSON.stringify({ text, speechId: `smoke-${Date.now()}` })
  },
  stdio: 'inherit',
  windowsHide: true
});

child.on('exit', (code, signal) => {
  if (signal) process.kill(process.pid, signal);
  process.exitCode = code ?? 1;
});

function readFlag(name) {
  const index = args.indexOf(name);
  if (index >= 0) return args[index + 1];
  const prefix = `${name}=`;
  const match = args.find((arg) => arg.startsWith(prefix));
  return match ? match.slice(prefix.length) : undefined;
}
