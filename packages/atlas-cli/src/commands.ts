import { resolve } from 'node:path';
import {
  FileSessionStore,
  applySessionEvent,
  createSessionState,
  inspectSession,
  type DeviceBinding,
  type DeviceCapability,
  type SessionStatus
} from '@atlas/core';

export type CliResult = {
  exitCode: number;
  stdout?: string;
  stderr?: string;
};

export type CliOptions = {
  cwd?: string;
  env?: NodeJS.ProcessEnv;
};

export async function runAtlasCli(argv: string[], options: CliOptions = {}): Promise<CliResult> {
  const [command, ...args] = argv;

  if (!command || command === 'help' || command === '--help' || command === '-h') {
    return ok(helpText());
  }

  if (command === 'shrug') return ok('¯\\_(ツ)_/¯');
  if (command === 'sessions') return runSessionsCommand(args, options);
  if (command === 'session') return runSessionCommand(args, options);

  return fail(`Unknown command: ${command}\n\n${helpText()}`, 1);
}

async function runSessionsCommand(args: string[], options: CliOptions): Promise<CliResult> {
  const [subcommand] = args;
  if (subcommand !== 'list') return fail('Usage: atlas sessions list [--store <path>]', 1);

  const store = createStore(args.slice(1), options);
  const sessionIds = await store.listSessionIds();
  return ok(JSON.stringify({ sessions: sessionIds }, null, 2));
}

async function runSessionCommand(args: string[], options: CliOptions): Promise<CliResult> {
  const [subcommand, sessionId, ...rest] = args;

  if (subcommand === 'create' && sessionId) return createSessionCommand(sessionId, rest, options);
  if (subcommand === 'inspect' && sessionId) return inspectSessionCommand(sessionId, rest, options);
  if (subcommand === 'start' && sessionId) return lifecycleSessionCommand(sessionId, 'session.started', 'active', rest, options);
  if (subcommand === 'pause' && sessionId) return lifecycleSessionCommand(sessionId, 'session.paused', 'paused', rest, options);
  if (subcommand === 'resume' && sessionId) return lifecycleSessionCommand(sessionId, 'session.resumed', 'active', rest, options);
  if ((subcommand === 'end' || subcommand === 'done') && sessionId) return lifecycleSessionCommand(sessionId, 'session.ended', 'done', rest, options);

  return fail('Usage: atlas session create <sessionId> [options]\n       atlas session inspect <sessionId> [--store <path>]\n       atlas session start|pause|resume|end <sessionId> [--reason <reason>] [--store <path>]', 1);
}

async function createSessionCommand(sessionId: string, args: string[], options: CliOptions): Promise<CliResult> {
  const store = createStore(args, options);
  const existing = await store.loadState(sessionId);
  if (existing) return fail(`Session already exists: ${sessionId}`, 1);

  const providerAdapter = readFlagValue(args, '--provider') ?? '@atlas/core/testing';
  const providerId = readFlagValue(args, '--provider-id') ?? providerAdapter;
  const devices = readRepeatedFlagValues(args, '--device').map(parseDeviceBinding);
  const now = readFlagValue(args, '--now');

  const session = createSessionState({
    sessionId,
    name: readFlagValue(args, '--name'),
    goal: readFlagValue(args, '--goal'),
    now,
    provider: {
      id: providerId,
      adapter: providerAdapter
    },
    devices
  });

  await store.create(session);
  await store.appendEvent(session.sessionId, {
    type: 'session.created',
    at: session.createdAt,
    data: {
      source: 'atlas-cli',
      provider: session.provider,
      deviceCount: session.devices.length
    }
  });

  return ok(JSON.stringify({ sessionId: session.sessionId, created: true, statePath: 'state.json' }, null, 2));
}

async function inspectSessionCommand(sessionId: string, args: string[], options: CliOptions): Promise<CliResult> {
  const store = createStore(args, options);
  const inspection = await inspectSession(store, sessionId);
  if (!inspection) return fail(`Session not found: ${sessionId}`, 1);
  return ok(JSON.stringify(inspection, null, 2));
}

async function lifecycleSessionCommand(
  sessionId: string,
  eventType: 'session.started' | 'session.paused' | 'session.resumed' | 'session.ended',
  targetStatus: SessionStatus,
  args: string[],
  options: CliOptions
): Promise<CliResult> {
  const store = createStore(args, options);
  const state = await store.loadState(sessionId);
  if (!state) return fail(`Session not found: ${sessionId}`, 1);
  if (state.status === targetStatus) return ok(JSON.stringify({ sessionId, status: state.status, changed: false }, null, 2));

  const event = await store.appendEvent(sessionId, {
    type: eventType,
    data: {
      source: 'atlas-cli',
      reason: readFlagValue(args, '--reason')
    }
  });
  const nextState = applySessionEvent(state, event);
  await store.saveState(nextState);

  return ok(JSON.stringify({ sessionId, status: nextState.status, changed: true, event: event.type }, null, 2));
}

function parseDeviceBinding(value: string): DeviceBinding {
  const [id, adapter, capabilitiesText] = value.split(':');
  if (!id || !adapter) {
    throw new Error(`Invalid --device value: ${value}. Expected id:adapter:capability,capability`);
  }

  return {
    id,
    adapter,
    capabilities: parseCapabilities(capabilitiesText)
  };
}

function parseCapabilities(value: string | undefined): DeviceCapability[] {
  if (!value) return [];
  return value
    .split(',')
    .map((capability) => capability.trim())
    .filter(Boolean) as DeviceCapability[];
}

function createStore(args: string[], options: CliOptions): FileSessionStore {
  return new FileSessionStore({ rootDir: resolveStoreRoot(args, options) });
}

export function resolveStoreRoot(args: string[], options: CliOptions = {}): string {
  const explicitStore = readFlagValue(args, '--store');
  const root = options.cwd ?? process.cwd();
  const envStore = options.env?.ATLAS_STORE ?? process.env.ATLAS_STORE;
  return resolve(root, explicitStore ?? envStore ?? '.atlas-cache/sessions');
}

function readFlagValue(args: string[], flag: string): string | undefined {
  const index = args.indexOf(flag);
  if (index < 0) return undefined;
  return args[index + 1];
}

function readRepeatedFlagValues(args: string[], flag: string): string[] {
  const values: string[] = [];
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === flag && args[index + 1]) values.push(args[index + 1]!);
  }
  return values;
}

function ok(stdout: string): CliResult {
  return { exitCode: 0, stdout };
}

function fail(stderr: string, exitCode: number): CliResult {
  return { exitCode, stderr };
}

function helpText(): string {
  return `Atlas CLI\n\nUsage:\n  atlas shrug\n  atlas sessions list [--store <path>]\n  atlas session create <sessionId> [--name <name>] [--goal <goal>] [--provider <adapter>] [--provider-id <id>] [--device <id:adapter:capability,capability>] [--store <path>]\n  atlas session inspect <sessionId> [--store <path>]\n  atlas session start <sessionId> [--reason <reason>] [--store <path>]\n  atlas session pause <sessionId> [--reason <reason>] [--store <path>]\n  atlas session resume <sessionId> [--reason <reason>] [--store <path>]\n  atlas session end <sessionId> [--reason <reason>] [--store <path>]\n  atlas help\n\nEnvironment:\n  ATLAS_STORE  Override default .atlas-cache/sessions store path`;
}
