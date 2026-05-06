#!/usr/bin/env node
import { spawn } from 'node:child_process';
import path from 'node:path';

try {
  const turn = await readJsonInput('ATLAS_PROVIDER_TURN');
  const result = await runOpenClawAgent(turn);
  process.stdout.write(`${JSON.stringify(result)}\n`);
} catch (error) {
  process.stderr.write(`${formatError(error)}\n`);
  process.exitCode = 1;
}

async function runOpenClawAgent(turn) {
  const providerMode = firstString(process.env.ATLAS_OPENCLAW_PROVIDER_MODE) ?? 'agent';
  if (providerMode === 'summary' || providerMode === 'visual-summary') return summarizeLatestObservation(turn);

  const openclaw = resolveOpenClawInvocation(firstString(process.env.ATLAS_OPENCLAW_BIN));
  const sessionPrefix = firstString(process.env.ATLAS_OPENCLAW_AGENT_SESSION_PREFIX) ?? 'atlas';
  const sessionId = `${sessionPrefix}-${safeSessionId(turn?.session?.sessionId ?? 'session')}`;
  const timeoutSeconds = firstString(process.env.ATLAS_OPENCLAW_AGENT_TIMEOUT_SECONDS) ?? '600';
  const message = buildOpenClawPrompt(turn);

  const args = ['agent', '--session-id', sessionId, '--message', message, '--json', '--timeout', timeoutSeconds];
  const agentId = firstString(process.env.ATLAS_OPENCLAW_AGENT_ID);
  const thinking = firstString(process.env.ATLAS_OPENCLAW_THINKING);
  if (agentId) args.push('--agent', agentId);
  if (thinking) args.push('--thinking', thinking);

  const output = await runCommand(openclaw.command, [...openclaw.args, ...args], {
    timeoutMs: Number(timeoutSeconds) * 1000 + 5000
  });

  return {
    turnId: turn.turnId,
    responseText: extractOpenClawText(output.stdout) || output.stdout.trim() || undefined
  };
}

function summarizeLatestObservation(turn) {
  const observations = Array.isArray(turn?.observations) ? turn.observations : [];
  const latest = observations.at(-1);
  const summary = latestObservationSummary(latest);
  return {
    turnId: turn?.turnId,
    responseText: summary
      ? `I’m seeing: ${summary}`
      : 'I do not have a usable visual summary yet.'
  };
}

function latestObservationSummary(observation) {
  if (!observation || typeof observation !== 'object') return undefined;
  const direct = firstString(observation.summary);
  if (direct) return direct;

  const analyses = Array.isArray(observation.analyses) ? observation.analyses : [];
  for (const kind of ['visual-summary', 'quality']) {
    const match = analyses.find((analysis) => analysis?.kind === kind && typeof analysis.summary === 'string' && analysis.summary.length > 0);
    if (match) return match.summary;
  }

  const anySummary = analyses.find((analysis) => typeof analysis?.summary === 'string' && analysis.summary.length > 0);
  return anySummary?.summary;
}

function buildOpenClawPrompt(turn) {
  const trigger = turn.trigger?.type === 'user' ? turn.trigger.text : `[${turn.trigger?.type ?? 'unknown'}] ${turn.trigger?.reason ?? ''}`;
  const observationLines = (turn.observations ?? []).map((observation, index) => {
    const analyses = (observation.analyses ?? [])
      .map((analysis) => `${analysis.kind}: ${analysis.summary ?? ''}`.trim())
      .filter(Boolean)
      .join('; ');
    return [
      `Observation ${index + 1}:`,
      `- type: ${observation.type}`,
      observation.mediaRef ? `- mediaRef: ${observation.mediaRef}` : undefined,
      observation.summary ? `- summary: ${observation.summary}` : undefined,
      analyses ? `- analyses: ${analyses}` : undefined,
      observation.capturedAt ? `- capturedAt: ${observation.capturedAt}` : undefined
    ]
      .filter(Boolean)
      .join('\n');
  });

  return [
    'You are the upstream OpenClaw agent inside an Atlas physical-session loop.',
    'Answer the user using the normalized Atlas context below. Keep the reply concise and actionable.',
    '',
    `Atlas session: ${turn.session?.sessionId ?? 'unknown'}`,
    `Turn id: ${turn.turnId ?? 'unknown'}`,
    `User/request: ${trigger ?? ''}`,
    '',
    'Context status:',
    JSON.stringify(turn.contextStatus ?? {}, null, 2),
    '',
    observationLines.length ? observationLines.join('\n\n') : 'No observations were supplied.',
    '',
    ...(Array.isArray(turn.instructions) && turn.instructions.length ? ['Atlas instructions:', ...turn.instructions] : [])
  ].join('\n');
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
      if (code !== 0) reject(new Error(`${command} ${args.join(' ')} exited with code ${code}.${stderr ? ` stderr: ${stderr.trim()}` : ''}`));
      else resolve({ stdout, stderr });
    });
  });
}

function extractOpenClawText(stdout) {
  const trimmed = stdout.trim();
  if (!trimmed) return undefined;

  const parsed = parseJsonObject(trimmed) ?? parseJsonObject(lastJsonLine(trimmed));
  if (!parsed) return undefined;

  return firstString(
    parsed.responseText,
    parsed.reply,
    parsed.message,
    parsed.text,
    parsed.content,
    parsed.payloads?.[0]?.text,
    parsed.result?.responseText,
    parsed.result?.reply,
    parsed.result?.message,
    parsed.result?.text,
    parsed.result?.payloads?.[0]?.text,
    parsed.assistant?.text,
    parsed.assistant?.message
  );
}

function parseJsonObject(text) {
  try {
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === 'object' ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function lastJsonLine(text) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .reverse()
    .find((line) => line.startsWith('{') && line.endsWith('}'));
}

function safeSessionId(value) {
  return String(value).replace(/[^a-zA-Z0-9_.:-]+/g, '-').slice(0, 96) || 'session';
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

function formatError(error) {
  return error instanceof Error ? error.message : String(error);
}
