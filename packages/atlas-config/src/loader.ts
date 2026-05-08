import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createSessionState, type AtlasSessionState } from '@atlas/core';
import type { AtlasConfig, AtlasSessionConfig } from './types.js';

export const DEFAULT_ATLAS_CONFIG_FILENAMES = ['atlas.config.json', 'atlas.json'];

export type LoadAtlasConfigOptions = {
  cwd?: string;
  path?: string;
};

export async function loadAtlasConfig(options: LoadAtlasConfigOptions = {}): Promise<AtlasConfig> {
  const configPath = resolveAtlasConfigPath(options);
  const raw = await readFile(configPath, 'utf8');
  return parseAtlasConfig(raw);
}

export function parseAtlasConfig(raw: string): AtlasConfig {
  const parsed = JSON.parse(raw) as AtlasConfig;
  validateAtlasConfig(parsed);
  return parsed;
}

export function resolveAtlasConfigPath(options: LoadAtlasConfigOptions = {}): string {
  const cwd = options.cwd ?? process.cwd();
  return resolve(cwd, options.path ?? DEFAULT_ATLAS_CONFIG_FILENAMES[0]!);
}

export function sessionStateFromConfig(input: {
  sessionId: string;
  config: AtlasSessionConfig;
  now?: string;
}): AtlasSessionState {
  const session = createSessionState({
    sessionId: input.sessionId,
    name: input.config.name,
    goal: input.config.goal,
    now: input.now,
    provider: input.config.provider ?? {
      id: '@atlas/core/testing',
      adapter: '@atlas/core/testing'
    },
    devices: input.config.devices ?? [],
    permissions: input.config.permissions
  });

  return {
    ...session,
    mode: input.config.mode ?? session.mode
  };
}

export function sessionStatesFromConfig(config: AtlasConfig, now?: string): AtlasSessionState[] {
  return Object.entries(config.sessions ?? {}).map(([sessionId, sessionConfig]) =>
    sessionStateFromConfig({ sessionId, config: sessionConfig, now })
  );
}

function validateAtlasConfig(config: AtlasConfig): void {
  if (typeof config !== 'object' || config === null || Array.isArray(config)) {
    throw new Error('Atlas config must be a JSON object.');
  }
  if (config.sessions !== undefined && (typeof config.sessions !== 'object' || config.sessions === null || Array.isArray(config.sessions))) {
    throw new Error('Atlas config sessions must be an object.');
  }
  for (const [sessionId, sessionConfig] of Object.entries(config.sessions ?? {})) {
    validateSessionHeartbeatConfig(sessionId, sessionConfig);
  }
}

function validateSessionHeartbeatConfig(sessionId: string, sessionConfig: AtlasSessionConfig): void {
  const heartbeat = sessionConfig.heartbeat;
  if (heartbeat === undefined) return;
  if (typeof heartbeat !== 'object' || heartbeat === null || Array.isArray(heartbeat)) {
    throw new Error(`Atlas config session ${sessionId} heartbeat must be an object.`);
  }
  validateOptionalFiniteNumber(heartbeat.intervalMs, `session ${sessionId} heartbeat.intervalMs`);
  const policy = heartbeat.policy;
  if (policy === undefined) return;
  if (typeof policy !== 'object' || policy === null || Array.isArray(policy)) {
    throw new Error(`Atlas config session ${sessionId} heartbeat.policy must be an object.`);
  }
  validateOptionalFiniteNumber(policy.minDelayMs, `session ${sessionId} heartbeat.policy.minDelayMs`);
  validateOptionalFiniteNumber(policy.maxDelayMs, `session ${sessionId} heartbeat.policy.maxDelayMs`);
  validateOptionalFiniteNumber(policy.baseStaleAfterMs, `session ${sessionId} heartbeat.policy.baseStaleAfterMs`);
  validateOptionalFiniteNumber(policy.minStaleAfterMs, `session ${sessionId} heartbeat.policy.minStaleAfterMs`);
  validateOptionalFiniteNumber(policy.maxStaleAfterMs, `session ${sessionId} heartbeat.policy.maxStaleAfterMs`);
  validateOptionalFiniteNumber(policy.expectedRefreshLatencyMs, `session ${sessionId} heartbeat.policy.expectedRefreshLatencyMs`);
  validateOptionalFiniteNumber(policy.minExpectedRefreshLatencyMs, `session ${sessionId} heartbeat.policy.minExpectedRefreshLatencyMs`);
  validateOptionalFiniteNumber(policy.maxExpectedRefreshLatencyMs, `session ${sessionId} heartbeat.policy.maxExpectedRefreshLatencyMs`);
  validateOptionalFiniteNumber(policy.refreshSafetyMarginMs, `session ${sessionId} heartbeat.policy.refreshSafetyMarginMs`);
  validateOptionalFiniteNumber(policy.refreshFailureRetryMs, `session ${sessionId} heartbeat.policy.refreshFailureRetryMs`);
  validateOptionalFiniteNumber(policy.providerReviewCooldownMs, `session ${sessionId} heartbeat.policy.providerReviewCooldownMs`);
  if (policy.cadence !== undefined) {
    if (typeof policy.cadence !== 'object' || policy.cadence === null || Array.isArray(policy.cadence)) {
      throw new Error(`Atlas config session ${sessionId} heartbeat.policy.cadence must be an object.`);
    }
    for (const [mode, delay] of Object.entries(policy.cadence)) {
      if (!['idle', 'stable-scene', 'active-task', 'unstable-scene', 'high-risk'].includes(mode)) {
        throw new Error(`Atlas config session ${sessionId} heartbeat.policy.cadence has unknown mode: ${mode}`);
      }
      validateOptionalFiniteNumber(delay, `session ${sessionId} heartbeat.policy.cadence.${mode}`);
    }
  }
}

function validateOptionalFiniteNumber(value: unknown, label: string): void {
  if (value === undefined) return;
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new Error(`Atlas config ${label} must be a non-negative finite number.`);
  }
}
