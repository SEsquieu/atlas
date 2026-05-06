import { spawn } from 'node:child_process';
import type { AgentProviderAdapter, NormalizedAgentResult, NormalizedSessionTurn } from '@atlas/core';

export type OpenClawCommandInputMode = 'env' | 'stdin';

export type OpenClawCommandProviderOptions = {
  id?: string;
  name?: string;
  command: string;
  args?: string[];
  cwd?: string;
  timeoutMs?: number;
  env?: Record<string, string>;
  inputMode?: OpenClawCommandInputMode;
};

export function createOpenClawCommandProviderAdapter(options: OpenClawCommandProviderOptions): AgentProviderAdapter {
  return {
    id: options.id ?? 'openclaw-command',
    name: options.name ?? 'OpenClaw Command Provider',
    step: async (turn) => runOpenClawProviderCommand(options, turn)
  };
}

export async function runOpenClawProviderCommand(
  options: OpenClawCommandProviderOptions,
  turn: NormalizedSessionTurn
): Promise<NormalizedAgentResult> {
  if (!options.command?.trim()) throw new Error('OpenClaw provider command is required.');
  const payload = JSON.stringify(turn);
  const output = await runCommand({
    command: options.command,
    args: options.args ?? [],
    cwd: options.cwd,
    timeoutMs: options.timeoutMs,
    env: {
      ...options.env,
      ATLAS_PROVIDER_TURN: payload
    },
    stdin: options.inputMode === 'stdin' ? payload : undefined
  });

  const trimmed = output.stdout.trim();
  if (!trimmed) return { turnId: turn.turnId };

  try {
    const parsed = JSON.parse(trimmed) as Partial<NormalizedAgentResult>;
    return {
      turnId: parsed.turnId ?? turn.turnId,
      ...parsed
    };
  } catch {
    return {
      turnId: turn.turnId,
      responseText: trimmed
    };
  }
}

async function runCommand(options: {
  command: string;
  args: string[];
  cwd?: string;
  timeoutMs?: number;
  env?: Record<string, string>;
  stdin?: string;
}): Promise<{ stdout: string; stderr: string }> {
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
          reject(new Error(`OpenClaw provider command timed out after ${options.timeoutMs}ms.`));
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
        reject(new Error(`OpenClaw provider command exited with code ${code}.${stderr ? ` stderr: ${stderr.trim()}` : ''}`));
        return;
      }
      resolve({ stdout, stderr });
    });

    if (options.stdin) child.stdin.end(options.stdin);
    else child.stdin.end();
  });
}
