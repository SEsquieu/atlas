import { spawn } from 'node:child_process';
import type { DeviceAdapter, DeviceCapability } from '@atlas/core';
import {
  normalizeAndroidBridgeCaptureResult,
  normalizeAndroidBridgeError,
  type AndroidBridgeCaptureOptions,
  type AndroidBridgeCaptureResult
} from './bridge-result.js';

export type AndroidBridgeCommandInputMode = 'env' | 'stdin';

export type AndroidBridgeCommandOptions = {
  command: string;
  args?: string[];
  cwd?: string;
  timeoutMs?: number;
  env?: Record<string, string>;
  inputMode?: AndroidBridgeCommandInputMode;
};

export type AndroidBridgeCommandDeviceAdapterOptions = AndroidBridgeCommandOptions & {
  id?: string;
  name?: string;
  facing?: 'back' | 'front';
  analyze?: boolean;
  analysisMode?: 'ollama' | 'openclaw' | 'none' | string;
};

export function createAndroidBridgeCommandDeviceAdapter(options: AndroidBridgeCommandDeviceAdapterOptions): DeviceAdapter {
  const deviceId = options.id ?? 'android-bridge-command';
  const capabilities: DeviceCapability[] = ['camera.capture'];

  return {
    id: deviceId,
    name: options.name ?? 'Android Bridge Command Device',
    capabilities: async () => capabilities,
    captureImage: async (captureOptions) => {
      try {
        const result = await runAndroidBridgeCommand(options, {
          ...captureOptions,
          facing: options.facing ?? 'back',
          analyze: options.analyze ?? true,
          analysisMode: options.analysisMode ?? 'ollama'
        });
        return normalizeAndroidBridgeCaptureResult(result, {
          deviceId,
          producedBy: 'openclaw/android-camera-bridge-command'
        });
      } catch (error) {
        throw normalizeAndroidBridgeError(error);
      }
    }
  };
}

export async function runAndroidBridgeCommand(
  options: AndroidBridgeCommandOptions,
  captureOptions?: AndroidBridgeCaptureOptions
): Promise<AndroidBridgeCaptureResult> {
  if (!options.command?.trim()) throw new Error('Android bridge command is required.');
  const payload = JSON.stringify(captureOptions ?? {});
  const output = await runJsonCommand({
    command: options.command,
    args: options.args ?? [],
    cwd: options.cwd,
    timeoutMs: options.timeoutMs,
    env: {
      ...options.env,
      ATLAS_ANDROID_BRIDGE_OPTIONS: payload
    },
    stdin: options.inputMode === 'stdin' ? payload : undefined
  });
  return output as AndroidBridgeCaptureResult;
}

async function runJsonCommand(options: {
  command: string;
  args: string[];
  cwd?: string;
  timeoutMs?: number;
  env?: Record<string, string>;
  stdin?: string;
}): Promise<unknown> {
  return await new Promise((resolve, reject) => {
    const child = spawn(options.command, options.args, {
      cwd: options.cwd,
      env: { ...process.env, ...options.env },
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true
    });

    const timeout = options.timeoutMs
      ? setTimeout(() => {
          child.kill();
          reject(new Error(`Android bridge command timed out after ${options.timeoutMs}ms.`));
        }, options.timeoutMs)
      : undefined;

    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => {
      if (timeout) clearTimeout(timeout);
      reject(error);
    });
    child.on('close', (code) => {
      if (timeout) clearTimeout(timeout);
      if (code !== 0) {
        reject(new Error(`Android bridge command exited with code ${code}.${stderr ? ` stderr: ${stderr.trim()}` : ''}`));
        return;
      }
      try {
        resolve(JSON.parse(stdout.trim()));
      } catch (error) {
        reject(new Error(`Android bridge command did not emit JSON.${stdout ? ` stdout: ${stdout.trim()}` : ''}`, { cause: error }));
      }
    });

    if (options.stdin) child.stdin.end(options.stdin);
    else child.stdin.end();
  });
}
