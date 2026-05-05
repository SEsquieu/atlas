import { resolve } from 'node:path';
import { FileSessionStore, inspectSession } from '@atlas/core';

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
  if (subcommand !== 'inspect' || !sessionId) return fail('Usage: atlas session inspect <sessionId> [--store <path>]', 1);

  const store = createStore(rest, options);
  const inspection = await inspectSession(store, sessionId);
  if (!inspection) return fail(`Session not found: ${sessionId}`, 1);
  return ok(JSON.stringify(inspection, null, 2));
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

function ok(stdout: string): CliResult {
  return { exitCode: 0, stdout };
}

function fail(stderr: string, exitCode: number): CliResult {
  return { exitCode, stderr };
}

function helpText(): string {
  return `Atlas CLI\n\nUsage:\n  atlas sessions list [--store <path>]\n  atlas session inspect <sessionId> [--store <path>]\n  atlas help\n\nEnvironment:\n  ATLAS_STORE  Override default .atlas-cache/sessions store path`;
}
