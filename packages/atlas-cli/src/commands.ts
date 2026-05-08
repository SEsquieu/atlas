import { resolve } from 'node:path';
import {
  loadAtlasConfig,
  resolveAtlasConfigPath,
  sessionStateFromConfig,
  sessionStatesFromConfig,
  type AtlasConfig,
  type AtlasHeartbeatPolicyConfig,
  type AtlasSessionConfig
} from '@atlas/config';
import {
  AtlasRunner,
  FileSessionStore,
  type AgentProviderAdapter,
  applySessionEvent,
  createFakeCameraDevice,
  createFakeProvider,
  createFakeVisualAnalyzer,
  createSessionState,
  createStaticAdapterRegistry,
  inspectSession,
  type DeviceAdapter,
  type DeviceBinding,
  type DeviceCapability,
  type HeartbeatPolicyOptions,
  type ProviderBinding,
  type SessionStatus
} from '@atlas/core';
import { createAndroidBridgeCommandDeviceAdapter } from '@atlas/device-android';
import { createOpenClawCommandProviderAdapter } from '@atlas/provider-openclaw';

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
  if (command === 'config') return runConfigCommand(args, options);
  if (command === 'sessions') return runSessionsCommand(args, options);
  if (command === 'session') return runSessionCommand(args, options);

  return fail(`Unknown command: ${command}\n\n${helpText()}`, 1);
}

async function runConfigCommand(args: string[], options: CliOptions): Promise<CliResult> {
  const [subcommand, ...rest] = args;
  if (subcommand !== 'inspect') return fail('Usage: atlas config inspect [--config <path>]', 1);

  const configPath = readFlagValue(rest, '--config');
  try {
    const config = await loadAtlasConfig({ cwd: options.cwd, path: configPath });
    const sessions = sessionStatesFromConfig(config).map((session) => ({
      sessionId: session.sessionId,
      name: session.name,
      goal: session.goal,
      mode: session.mode,
      provider: session.provider,
      devices: session.devices
    }));

    return ok(
      JSON.stringify(
        {
          configPath: resolveAtlasConfigPath({ cwd: options.cwd, path: configPath }),
          storePath: config.store?.path,
          sessions
        },
        null,
        2
      )
    );
  } catch (error) {
    return fail(error instanceof Error ? error.message : String(error), 1);
  }
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
  if (subcommand === 'ask' && sessionId) return askSessionCommand(sessionId, rest, options);
  if (subcommand === 'heartbeat' && sessionId) return heartbeatSessionCommand(sessionId, rest, options);
  if (subcommand === 'start' && sessionId) return lifecycleSessionCommand(sessionId, 'session.started', 'active', rest, options);
  if (subcommand === 'pause' && sessionId) return lifecycleSessionCommand(sessionId, 'session.paused', 'paused', rest, options);
  if (subcommand === 'resume' && sessionId) return lifecycleSessionCommand(sessionId, 'session.resumed', 'active', rest, options);
  if ((subcommand === 'end' || subcommand === 'done') && sessionId) return lifecycleSessionCommand(sessionId, 'session.ended', 'done', rest, options);

  return fail('Usage: atlas session create <sessionId> [options] [--config <path>]\n       atlas session inspect <sessionId> [--store <path>]\n       atlas session ask <sessionId> --text <text> [--store <path>] [--config <path>]\n       atlas session heartbeat <sessionId> [--store <path>] [--config <path>]\n       atlas session start|pause|resume|end <sessionId> [--reason <reason>] [--store <path>]', 1);
}

async function createSessionCommand(sessionId: string, args: string[], options: CliOptions): Promise<CliResult> {
  const store = createStore(args, options);
  const existing = await store.loadState(sessionId);
  if (existing) return fail(`Session already exists: ${sessionId}`, 1);

  const config = await loadOptionalConfig(args, options);
  if ('error' in config) return fail(config.error, 1);
  const configuredSession = config.config?.sessions?.[sessionId];
  if (config.config && !configuredSession) return fail(`Session not found in config: ${sessionId}`, 1);

  const providerAdapter = readFlagValue(args, '--provider') ?? '@atlas/core/testing';
  const providerId = readFlagValue(args, '--provider-id') ?? providerAdapter;
  const devices = readRepeatedFlagValues(args, '--device').map(parseDeviceBinding);
  const now = readFlagValue(args, '--now');

  const session = configuredSession
    ? sessionStateFromConfig({ sessionId, config: configuredSession, now })
    : createSessionState({
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

  return ok(JSON.stringify({ sessionId: session.sessionId, created: true, statePath: 'state.json', fromConfig: Boolean(configuredSession) }, null, 2));
}

async function inspectSessionCommand(sessionId: string, args: string[], options: CliOptions): Promise<CliResult> {
  const store = createStore(args, options);
  const inspection = await inspectSession(store, sessionId);
  if (!inspection) return fail(`Session not found: ${sessionId}`, 1);
  return ok(JSON.stringify(inspection, null, 2));
}

async function askSessionCommand(sessionId: string, args: string[], options: CliOptions): Promise<CliResult> {
  const text = readFlagValue(args, '--text');
  if (!text) return fail('Usage: atlas session ask <sessionId> --text <text> [--store <path>] [--config <path>]', 1);

  const store = createStore(args, options);
  const config = await loadOptionalConfig(args, options);
  if ('error' in config) return fail(config.error, 1);
  const runnerResult = await createCliRunner(sessionId, store, config.config);
  if ('error' in runnerResult) return fail(runnerResult.error, 1);

  try {
    const result = await runnerResult.runner.runUserTurn({ sessionId, text });
    return ok(
      JSON.stringify(
        {
          sessionId,
          responseText: result.providerResult.responseText,
          plan: result.plan,
          refreshedObservationId: result.refreshedObservation?.id,
          refreshError: result.refreshError,
          reusedLastObservationAfterRefreshFailure: result.reusedLastObservationAfterRefreshFailure,
          status: result.session.status
        },
        null,
        2
      )
    );
  } catch (error) {
    return fail(`session ask failed: ${formatError(error)}`, 1);
  }
}

async function heartbeatSessionCommand(sessionId: string, args: string[], options: CliOptions): Promise<CliResult> {
  const store = createStore(args, options);
  const config = await loadOptionalConfig(args, options);
  if ('error' in config) return fail(config.error, 1);
  const runnerResult = await createCliRunner(sessionId, store, config.config);
  if ('error' in runnerResult) return fail(runnerResult.error, 1);

  try {
    const result = await runnerResult.runner.runHeartbeatTick({ sessionId });
    return ok(
      JSON.stringify(
        {
          sessionId,
          decision: result.decision,
          observationId: result.observation?.id,
          significance: result.significance,
          providerResult: result.providerResult
            ? {
                responseText: result.providerResult.responseText,
                done: result.providerResult.done
              }
            : undefined,
          proactiveSpeechSuppressed: result.proactiveSpeechSuppressed,
          status: result.session.status
        },
        null,
        2
      )
    );
  } catch (error) {
    return fail(`session heartbeat failed: ${formatError(error)}`, 1);
  }
}

async function createCliRunner(sessionId: string, store: FileSessionStore, config?: AtlasConfig): Promise<{ runner: AtlasRunner } | { error: string }> {
  const state = await store.loadState(sessionId);
  if (!state) return { error: `Session not found: ${sessionId}` };
  const sessionConfig = config?.sessions?.[sessionId];
  const providerBinding = sessionConfig?.provider ?? state.provider;
  const deviceBindings = sessionConfig?.devices ?? state.devices;

  const registry = createCliAdapterRegistry(sessionConfig ?? { provider: providerBinding, devices: deviceBindings });
  const provider = registry.resolveProvider(providerBinding);
  if (!provider) return { error: `Provider adapter not available: ${providerBinding.adapter}` };

  return {
    runner: new AtlasRunner({
      store,
      provider,
      devices: registry.resolveDevices(deviceBindings),
      analyzers: registry.resolveAnalyzers?.(sessionConfig?.analyzers),
      heartbeatPolicy: heartbeatPolicyFromConfig(sessionConfig?.heartbeat?.policy)
    })
  };
}

function heartbeatPolicyFromConfig(config: AtlasHeartbeatPolicyConfig | undefined): Omit<HeartbeatPolicyOptions, 'captureBudget'> | undefined {
  if (!config) return undefined;
  return {
    cadence: config.cadence,
    minDelayMs: config.minDelayMs,
    maxDelayMs: config.maxDelayMs,
    baseStaleAfterMs: config.baseStaleAfterMs,
    minStaleAfterMs: config.minStaleAfterMs,
    maxStaleAfterMs: config.maxStaleAfterMs,
    expectedRefreshLatencyMs: config.expectedRefreshLatencyMs,
    minExpectedRefreshLatencyMs: config.minExpectedRefreshLatencyMs,
    maxExpectedRefreshLatencyMs: config.maxExpectedRefreshLatencyMs,
    refreshSafetyMarginMs: config.refreshSafetyMarginMs,
    refreshFailureRetryMs: config.refreshFailureRetryMs
  };
}

function createCliAdapterRegistry(sessionConfig: Pick<AtlasSessionConfig, 'provider' | 'devices' | 'analyzers'>) {
  const providerId = sessionConfig.provider?.id ?? '@atlas/core/testing';
  const providerAdapter = sessionConfig.provider?.adapter ?? '@atlas/core/testing';
  const devices: DeviceAdapter[] = sessionConfig.devices?.length
    ? sessionConfig.devices.map(createDeviceFromBinding)
    : [createFakeCameraDevice({ includeSummary: false })];
  const analyzers = sessionConfig.analyzers
    ? sessionConfig.analyzers.map((id) => createFakeVisualAnalyzer({ id, name: id, summary: 'CLI fake visual analyzer summary.', confidence: 0.9 }))
    : [createFakeVisualAnalyzer({ summary: 'CLI fake visual analyzer summary.', confidence: 0.9 })];
  const provider = createProviderFromBinding(sessionConfig.provider ?? { id: providerId, adapter: providerAdapter });

  return createStaticAdapterRegistry({
    providers: [
      provider,
      createFakeProvider({ id: '@atlas/core/testing', name: '@atlas/core/testing' })
    ],
    devices,
    analyzers
  });
}

function createProviderFromBinding(binding: ProviderBinding): AgentProviderAdapter {
  if (binding.adapter === '@atlas/provider-openclaw/command') {
    const config = readObjectConfig(binding.config, binding.adapter);
    return createOpenClawCommandProviderAdapter({
      id: binding.id,
      name: readOptionalString(config, 'name') ?? binding.adapter,
      command: readRequiredString(config, 'command', binding.adapter),
      args: readOptionalStringArray(config, 'args'),
      cwd: readOptionalString(config, 'cwd'),
      timeoutMs: readOptionalNumber(config, 'timeoutMs'),
      env: readOptionalStringRecord(config, 'env'),
      inputMode: readOptionalInputMode(config, 'inputMode')
    });
  }

  return createFakeProvider({ id: binding.id, name: binding.adapter });
}

function createDeviceFromBinding(binding: DeviceBinding): DeviceAdapter {
  if (binding.adapter === '@atlas/device-android/bridge-command') {
    const config = readObjectConfig(binding.config, binding.adapter);
    return createAndroidBridgeCommandDeviceAdapter({
      id: binding.id,
      name: binding.name ?? readOptionalString(config, 'name') ?? binding.adapter,
      command: readRequiredString(config, 'command', binding.adapter),
      args: readOptionalStringArray(config, 'args'),
      cwd: readOptionalString(config, 'cwd'),
      timeoutMs: readOptionalNumber(config, 'timeoutMs'),
      env: readOptionalStringRecord(config, 'env'),
      inputMode: readOptionalInputMode(config, 'inputMode'),
      facing: readOptionalFacing(config, 'facing'),
      analyze: readOptionalBoolean(config, 'analyze'),
      analysisMode: readOptionalString(config, 'analysisMode'),
      maxWidth: readOptionalNumber(config, 'maxWidth'),
      quality: readOptionalQuality(config, 'quality'),
      delayMs: readOptionalNumber(config, 'delayMs')
    });
  }

  return createFakeCameraDevice({ id: binding.id, name: binding.name ?? binding.adapter, includeSummary: false });
}

function readObjectConfig(config: Record<string, unknown> | undefined, adapter: string): Record<string, unknown> {
  if (!config || typeof config !== 'object' || Array.isArray(config)) {
    throw new Error(`Adapter ${adapter} requires an object config.`);
  }
  return config;
}

function readRequiredString(config: Record<string, unknown>, key: string, adapter: string): string {
  const value = readOptionalString(config, key);
  if (!value) throw new Error(`Adapter ${adapter} requires config.${key}.`);
  return value;
}

function readOptionalString(config: Record<string, unknown>, key: string): string | undefined {
  const value = config[key];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function readOptionalNumber(config: Record<string, unknown>, key: string): number | undefined {
  const value = config[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function readOptionalBoolean(config: Record<string, unknown>, key: string): boolean | undefined {
  const value = config[key];
  return typeof value === 'boolean' ? value : undefined;
}

function readOptionalStringArray(config: Record<string, unknown>, key: string): string[] | undefined {
  const value = config[key];
  if (!Array.isArray(value)) return undefined;
  return value.filter((entry): entry is string => typeof entry === 'string');
}

function readOptionalStringRecord(config: Record<string, unknown>, key: string): Record<string, string> | undefined {
  const value = config[key];
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  return Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string'));
}

function readOptionalInputMode(config: Record<string, unknown>, key: string): 'env' | 'stdin' | undefined {
  const value = config[key];
  return value === 'env' || value === 'stdin' ? value : undefined;
}

function readOptionalFacing(config: Record<string, unknown>, key: string): 'back' | 'front' | undefined {
  const value = config[key];
  return value === 'back' || value === 'front' ? value : undefined;
}

function readOptionalQuality(config: Record<string, unknown>, key: string): 'low' | 'medium' | 'high' | number | undefined {
  const value = config[key];
  if (value === 'low' || value === 'medium' || value === 'high') return value;
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

async function loadOptionalConfig(args: string[], options: CliOptions): Promise<{ config?: AtlasConfig } | { error: string }> {
  const configPath = readFlagValue(args, '--config');
  if (!configPath) return {};
  try {
    return { config: await loadAtlasConfig({ cwd: options.cwd, path: configPath }) };
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) };
  }
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

function formatError(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

function helpText(): string {
  return `Atlas CLI\n\nUsage:\n  atlas shrug\n  atlas sessions list [--store <path>]\n  atlas session create <sessionId> [--name <name>] [--goal <goal>] [--provider <adapter>] [--provider-id <id>] [--device <id:adapter:capability,capability>] [--store <path>] [--config <path>]\n  atlas session inspect <sessionId> [--store <path>]\n  atlas session ask <sessionId> --text <text> [--store <path>] [--config <path>]\n  atlas session heartbeat <sessionId> [--store <path>] [--config <path>]\n  atlas session start <sessionId> [--reason <reason>] [--store <path>]\n  atlas session pause <sessionId> [--reason <reason>] [--store <path>]\n  atlas session resume <sessionId> [--reason <reason>] [--store <path>]\n  atlas session end <sessionId> [--reason <reason>] [--store <path>]\n  atlas config inspect [--config <path>]\n  atlas help\n\nEnvironment:\n  ATLAS_STORE  Override default .atlas-cache/sessions store path`;
}
