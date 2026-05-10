import { spawn } from 'node:child_process';
import type { DeviceAdapter, DeviceCapability, SpeakOptions, StopSpeakingOptions, TranscribeOnceOptions, TranscriptResult } from '@atlas/core';
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

export type AndroidBridgeSpeakCommandOptions = AndroidBridgeCommandOptions & {
  stopCommand?: string;
  stopArgs?: string[];
  stopTimeoutMs?: number;
};

export type AndroidBridgeSpeakInput = SpeakOptions & {
  text: string;
};

export type AndroidBridgeTranscribeCommandOptions = AndroidBridgeCommandOptions;

export type AndroidBridgeCommandDeviceAdapterOptions = AndroidBridgeCommandOptions & {
  id?: string;
  name?: string;
  facing?: 'back' | 'front';
  analyze?: boolean;
  analysisMode?: 'ollama' | 'openclaw' | 'none' | string;
  maxWidth?: number;
  quality?: 'low' | 'medium' | 'high' | number;
  delayMs?: number;
  speak?: AndroidBridgeSpeakCommandOptions;
  transcribe?: AndroidBridgeTranscribeCommandOptions;
};

export function createAndroidBridgeCommandDeviceAdapter(options: AndroidBridgeCommandDeviceAdapterOptions): DeviceAdapter {
  const deviceId = options.id ?? 'android-bridge-command';
  const capabilities: DeviceCapability[] = [
    'camera.capture',
    ...(options.speak ? (['audio.speak'] satisfies DeviceCapability[]) : []),
    ...(options.transcribe ? (['audio.listen'] satisfies DeviceCapability[]) : [])
  ];

  return {
    id: deviceId,
    name: options.name ?? 'Android Bridge Command Device',
    capabilities: async () => capabilities,
    captureImage: async (captureOptions) => {
      try {
        const requestedOptions = captureOptions as AndroidBridgeCaptureOptions | undefined;
        const result = await runAndroidBridgeCommand(options, {
          ...requestedOptions,
          facing: options.facing ?? 'back',
          analyze: options.analyze ?? true,
          analysisMode: options.analysisMode ?? 'ollama',
          maxWidth: options.maxWidth ?? requestedOptions?.maxWidth,
          quality: options.quality ?? requestedOptions?.quality,
          delayMs: options.delayMs ?? requestedOptions?.delayMs
        });
        return normalizeAndroidBridgeCaptureResult(result, {
          deviceId,
          producedBy: 'openclaw/android-camera-bridge-command'
        });
      } catch (error) {
        throw normalizeAndroidBridgeError(error);
      }
    },
    speak: options.speak
      ? async (text, speakOptions) => {
          await runAndroidBridgeSpeakCommand(options.speak!, { ...speakOptions, text });
        }
      : undefined,
    stopSpeaking: options.speak?.stopCommand
      ? async (stopOptions) => {
          await runAndroidBridgeStopSpeakingCommand(options.speak!, stopOptions);
        }
      : undefined,
    transcribeOnce: options.transcribe
      ? async (transcribeOptions) => await runAndroidBridgeTranscribeOnceCommand(options.transcribe!, transcribeOptions)
      : undefined
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

export async function runAndroidBridgeSpeakCommand(options: AndroidBridgeCommandOptions, speakInput: AndroidBridgeSpeakInput): Promise<unknown> {
  if (!options.command?.trim()) throw new Error('Android bridge speak command is required.');
  if (!speakInput.text?.trim()) throw new Error('Android bridge speak text is required.');
  const payload = JSON.stringify(speakInput);
  return await runJsonCommand({
    command: options.command,
    args: options.args ?? [],
    cwd: options.cwd,
    timeoutMs: options.timeoutMs,
    env: {
      ...options.env,
      ATLAS_ANDROID_BRIDGE_SPEAK: payload
    },
    stdin: options.inputMode === 'stdin' ? payload : undefined
  });
}

export async function runAndroidBridgeStopSpeakingCommand(options: AndroidBridgeSpeakCommandOptions, stopOptions?: StopSpeakingOptions): Promise<unknown> {
  if (!options.stopCommand?.trim()) throw new Error('Android bridge stop-speaking command is required.');
  const payload = JSON.stringify(stopOptions ?? {});
  return await runJsonCommand({
    command: options.stopCommand,
    args: options.stopArgs ?? [],
    cwd: options.cwd,
    timeoutMs: options.stopTimeoutMs ?? options.timeoutMs,
    env: {
      ...options.env,
      ATLAS_ANDROID_BRIDGE_STOP_SPEAKING: payload
    },
    stdin: options.inputMode === 'stdin' ? payload : undefined
  });
}

export async function runAndroidBridgeTranscribeOnceCommand(
  options: AndroidBridgeTranscribeCommandOptions,
  transcribeOptions?: TranscribeOnceOptions
): Promise<TranscriptResult> {
  if (!options.command?.trim()) throw new Error('Android bridge transcribe command is required.');
  const payload = JSON.stringify(transcribeOptions ?? {});
  const output = await runJsonCommand({
    command: options.command,
    args: options.args ?? [],
    cwd: options.cwd,
    timeoutMs: options.timeoutMs,
    env: {
      ...options.env,
      ATLAS_ANDROID_BRIDGE_TRANSCRIBE: payload
    },
    stdin: options.inputMode === 'stdin' ? payload : undefined
  });
  return normalizeTranscriptResult(output);
}

function normalizeTranscriptResult(output: unknown): TranscriptResult {
  if (!output || typeof output !== 'object') throw new Error('Android bridge transcribe command returned invalid JSON.');
  const raw = output as Record<string, unknown>;
  const status = typeof raw.status === 'string' ? raw.status : raw.ok === true ? 'ok' : 'error';
  if (!['ok', 'empty', 'timeout', 'cancelled', 'error'].includes(status)) throw new Error(`Android bridge transcribe command returned invalid status: ${status}`);
  return {
    transcript: typeof raw.transcript === 'string' ? raw.transcript : undefined,
    status: status as TranscriptResult['status'],
    confidence: typeof raw.confidence === 'number' && Number.isFinite(raw.confidence) ? raw.confidence : undefined,
    language: typeof raw.language === 'string' ? raw.language : undefined,
    alternatives: Array.isArray(raw.alternatives) ? raw.alternatives.filter((entry): entry is string => typeof entry === 'string') : undefined,
    captureId: typeof raw.captureId === 'string' ? raw.captureId : undefined,
    startedAt: typeof raw.startedAt === 'string' ? raw.startedAt : undefined,
    completedAt: typeof raw.completedAt === 'string' ? raw.completedAt : undefined,
    error: typeof raw.error === 'string' ? raw.error : undefined,
    data: raw
  };
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
