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
}
