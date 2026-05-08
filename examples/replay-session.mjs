#!/usr/bin/env node
import { existsSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  FileSessionStore,
  createSessionState,
  materializeSession,
  summarizeSession
} from '@atlas/core';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const args = parseArgs(process.argv.slice(2));
const storeRoot = path.resolve(repoRoot, args.store ?? path.join('.atlas-runs', 'scenario-harness'));
const sessionId = args.session ?? args._[0];

try {
  if (!sessionId) throw new Error(helpText());
  const store = new FileSessionStore({ rootDir: storeRoot });
  const record = await store.load(sessionId);
  if (!record) throw new Error(`Session not found: ${sessionId} in ${storeRoot}`);

  const report = buildReplayReport(record.state, record.events);
  printReport(report);

  if (args.json || args.markdown) await writeReports(report, args);
  if (!report.consistent) process.exitCode = 1;
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}

function buildReplayReport(savedState, events) {
  const baseState = createReplayBaseState(savedState);
  const replayedState = materializeSession(baseState, events);
  const savedSummary = summarizeSession(savedState, events);
  const replayedSummary = summarizeSession(replayedState, events);
  const comparisons = compareStates(savedState, replayedState);
  const failures = comparisons.filter((comparison) => !comparison.match).map((comparison) => comparison.label);

  return {
    kind: 'atlas.session-replay-report.v1',
    generatedAt: new Date().toISOString(),
    sessionId: savedState.sessionId,
    storeRoot,
    eventCount: events.length,
    firstEventAt: events.at(0)?.at,
    lastEventAt: events.at(-1)?.at,
    savedCheckpoint: savedState.eventCursor,
    replayedCheckpoint: replayedState.eventCursor,
    materializedThroughLatestEvent: replayedState.eventCursor?.lastEventId === events.at(-1)?.id || events.length === 0,
    consistent: failures.length === 0,
    failures,
    comparisons,
    saved: compactState(savedState, savedSummary),
    replayed: compactState(replayedState, replayedSummary),
    events: {
      byType: countBy(events.map((event) => event.type)),
      timeline: events.map((event) => ({ id: event.id, type: event.type, at: event.at, summary: summarizeEvent(event) }))
    }
  };
}

function createReplayBaseState(savedState) {
  return createSessionState({
    sessionId: savedState.sessionId,
    name: savedState.name,
    goal: savedState.goal,
    provider: savedState.provider,
    devices: savedState.devices,
    permissions: savedState.permissions,
    now: savedState.createdAt
  });
}

function compareStates(savedState, replayedState) {
  return [
    compare('status', savedState.status, replayedState.status),
    compare('updatedAt', savedState.updatedAt, replayedState.updatedAt),
    compare('eventCursor.lastEventId', savedState.eventCursor?.lastEventId, replayedState.eventCursor?.lastEventId),
    compare('eventCursor.lastEventAt', savedState.eventCursor?.lastEventAt, replayedState.eventCursor?.lastEventAt),
    compare('perception.latestImageId', savedState.perception.latestImageId, replayedState.perception.latestImageId),
    compare('perception.latestObservationAt', savedState.perception.latestObservationAt, replayedState.perception.latestObservationAt),
    compare('perception.summary', savedState.perception.summary, replayedState.perception.summary),
    compare('perception.confidence', savedState.perception.confidence, replayedState.perception.confidence),
    compare('perception.stability', savedState.perception.stability, replayedState.perception.stability),
    compare('recentObservations.ids', savedState.recentObservations.map((obs) => obs.id), replayedState.recentObservations.map((obs) => obs.id)),
    compare('memory', savedState.memory, replayedState.memory)
  ];
}

function compare(label, saved, replayed) {
  return {
    label,
    match: stableJson(saved) === stableJson(replayed),
    saved,
    replayed
  };
}

function compactState(state, summary) {
  return {
    status: state.status,
    updatedAt: state.updatedAt,
    eventCursor: state.eventCursor,
    perception: {
      latestObservationAt: state.perception.latestObservationAt,
      latestImageId: state.perception.latestImageId,
      summary: state.perception.summary,
      confidence: state.perception.confidence,
      freshnessMs: state.perception.freshnessMs,
      stability: state.perception.stability,
      motionState: state.perception.motionState
    },
    observations: {
      count: state.recentObservations.length,
      ids: state.recentObservations.map((obs) => obs.id),
      latest: summary.observations.latest
    },
    events: summary.events
  };
}

function printReport(report) {
  console.log('Atlas session replay');
  console.log(`- result: ${report.consistent ? 'PASS' : 'FAIL'}`);
  console.log(`- session: ${report.sessionId}`);
  console.log(`- store: ${report.storeRoot}`);
  console.log(`- events: ${report.eventCount}`);
  console.log(`- event types: ${formatCounts(report.events.byType)}`);
  console.log(`- saved cursor: ${report.savedCheckpoint?.lastEventId ?? 'none'}`);
  console.log(`- replayed cursor: ${report.replayedCheckpoint?.lastEventId ?? 'none'}`);
  console.log(`- replayed through latest event: ${report.materializedThroughLatestEvent ? 'yes' : 'no'}`);
  if (report.failures.length) {
    console.log('');
    console.log('Mismatches:');
    for (const failure of report.failures) console.log(`- ${failure}`);
  }
}

async function writeReports(report, options) {
  if (options.json) {
    const jsonPath = path.resolve(repoRoot, options.json);
    await mkdir(path.dirname(jsonPath), { recursive: true });
    await writeFile(jsonPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  }
  if (options.markdown) {
    const markdownPath = path.resolve(repoRoot, options.markdown);
    await mkdir(path.dirname(markdownPath), { recursive: true });
    await writeFile(markdownPath, formatMarkdownReport(report), 'utf8');
  }
}

function formatMarkdownReport(report) {
  const lines = [
    '# Atlas Session Replay Report',
    '',
    `- result: ${report.consistent ? 'PASS' : 'FAIL'}`,
    `- generated: ${report.generatedAt}`,
    `- session: ${report.sessionId}`,
    `- store: ${report.storeRoot}`,
    `- events: ${report.eventCount}`,
    `- event types: ${formatCounts(report.events.byType)}`,
    `- saved cursor: ${report.savedCheckpoint?.lastEventId ?? 'none'}`,
    `- replayed cursor: ${report.replayedCheckpoint?.lastEventId ?? 'none'}`,
    `- replayed through latest event: ${report.materializedThroughLatestEvent ? 'yes' : 'no'}`,
    '',
    '## Comparisons',
    '',
    ...report.comparisons.map((comparison) => `- ${comparison.match ? 'PASS' : 'FAIL'} ${comparison.label}`),
    '',
    '## Event timeline',
    '',
    ...report.events.timeline.map((event) => `- ${event.at} ${event.type}${event.summary ? ` — ${event.summary}` : ''}`),
    ''
  ];
  return `${lines.join('\n')}`;
}

function summarizeEvent(event) {
  const data = typeof event.data === 'object' && event.data !== null ? event.data : undefined;
  if (!data) return undefined;
  if (event.type === 'user.utterance' && typeof data.text === 'string') return data.text;
  if ((event.type === 'agent.speech' || event.type === 'agent.speech_suppressed') && typeof data.text === 'string') return data.text;
  if (event.type === 'tool.requested' && typeof data.toolName === 'string') return `${data.toolName}${typeof data.reason === 'string' ? `: ${data.reason}` : ''}`;
  if (event.type === 'tool.completed' && typeof data.toolName === 'string') return `${data.toolName} completed`;
  if (event.type === 'observation.captured') return data.observation?.summary ?? data.observation?.id;
  if (event.type === 'perception.significance') return data.decision?.reason ?? data.decision?.level;
  if (event.type === 'provider.requested') return data.source ? `source=${data.source}` : undefined;
  if (event.type === 'provider.responded') return data.result?.responseText;
  return undefined;
}

function countBy(values) {
  const counts = {};
  for (const value of values) counts[value] = (counts[value] ?? 0) + 1;
  return counts;
}

function formatCounts(counts) {
  return Object.entries(counts).map(([key, count]) => `${key}=${count}`).join(', ') || 'none';
}

function stableJson(value) {
  return JSON.stringify(sortKeys(value));
}

function sortKeys(value) {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (typeof value !== 'object' || value === null) return value;
  return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, val]) => [key, sortKeys(val)]));
}

function parseArgs(raw) {
  const parsed = { _: [] };
  for (let index = 0; index < raw.length; index += 1) {
    const arg = raw[index];
    if (arg === '--store') parsed.store = raw[++index];
    else if (arg === '--session') parsed.session = raw[++index];
    else if (arg === '--json') parsed.json = raw[++index];
    else if (arg === '--markdown') parsed.markdown = raw[++index];
    else if (arg === '--help' || arg === '-h') {
      console.log(helpText());
      process.exit(0);
    } else {
      parsed._.push(arg);
    }
  }
  return parsed;
}

function helpText() {
  return `Atlas session replay/debug\n\nUsage:\n  npm run replay:session -- <sessionId> [--store .atlas-runs/scenario-harness]\n  npm run replay:session -- --session heartbeat-actionable-escalation --json replay.json --markdown replay.md\n\nRebuilds a session from its event log, compares the replayed materialized state to saved state, and prints a compact debug report. Exits nonzero if replayed state diverges from saved state.`;
}
