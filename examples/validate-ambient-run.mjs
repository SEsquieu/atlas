#!/usr/bin/env node
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const args = parseArgs(process.argv.slice(2));
const defaultStore = path.join(repoRoot, '.atlas-runs', 'latest-ambient-android');
const defaultSession = 'live-android-openclaw';
const jsonlPath = resolveAmbientJsonlPath(args);

try {
  const entries = await readJsonlEntries(jsonlPath);
  const report = validateAmbientRun(entries, args);
  printReport(report);
  if (report.failures.length > 0) process.exitCode = 1;
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}

function validateAmbientRun(entries, options) {
  const heartbeatEntries = entries.filter(isHeartbeatEntry);
  const cachedAskEntries = entries.filter((entry) => entry.type === 'cached-ask');
  const captureEntries = heartbeatEntries.filter((entry) => entry.captured);
  const staleReuses = heartbeatEntries.filter((entry) => entry.freshness?.stale === true && !entry.captured);
  const toleratedStaleFallbacks = staleReuses.filter(isToleratedStaleFallback);
  const failingStaleReuses = options.failOnDegradedStaleReuse
    ? staleReuses
    : staleReuses.filter((entry) => !toleratedStaleFallbacks.includes(entry));
  const refreshDueReuses = heartbeatEntries.filter((entry) => entry.freshness?.refreshDue === true && entry.freshness?.stale !== true && !entry.captured);
  const askRefreshes = cachedAskEntries.filter((entry) => entry.refreshedObservationId);
  const askFallbacks = cachedAskEntries.filter((entry) => entry.reusedLastObservationAfterRefreshFailure);
  const askWallValues = cachedAskEntries.map((entry) => entry.wallMs).filter(isFiniteNumber);
  const heartbeatWallValues = heartbeatEntries.map((entry) => entry.wallMs).filter(isFiniteNumber);
  const failures = [];
  const warnings = [];

  const minHeartbeats = options.minHeartbeats ?? 1;
  const minCaptures = options.minCaptures ?? (options.requireCapture ? 1 : 0);
  const minCachedAsks = options.minCachedAsks ?? (options.requireCachedAsk ? 1 : 0);

  if (heartbeatEntries.length < minHeartbeats) failures.push(`expected at least ${minHeartbeats} heartbeat tick(s), found ${heartbeatEntries.length}`);
  if (captureEntries.length < minCaptures) failures.push(`expected at least ${minCaptures} heartbeat capture(s), found ${captureEntries.length}`);
  if (cachedAskEntries.length < minCachedAsks) failures.push(`expected at least ${minCachedAsks} cached ask(s), found ${cachedAskEntries.length}`);
  if (failingStaleReuses.length > (options.maxStaleReuses ?? 0)) failures.push(`stale visual context was reused unsafely ${failingStaleReuses.length} time(s)`);
  if (isFiniteNumber(options.maxRefreshDueReuses) && refreshDueReuses.length > options.maxRefreshDueReuses) failures.push(`refresh-due visual context was reused ${refreshDueReuses.length} time(s), max ${options.maxRefreshDueReuses}`);
  if (isFiniteNumber(options.maxAskRefreshes) && askRefreshes.length > options.maxAskRefreshes) failures.push(`ask-time refresh happened ${askRefreshes.length} time(s), max ${options.maxAskRefreshes}`);
  if (isFiniteNumber(options.maxAskWallMs) && askWallValues.some((value) => value > options.maxAskWallMs)) failures.push(`cached ask wall time exceeded ${formatMs(options.maxAskWallMs)}`);
  if (askFallbacks.length > 0) warnings.push(`ask fallback reused latest observation after refresh failure ${askFallbacks.length} time(s)`);
  if (toleratedStaleFallbacks.length > 0) warnings.push(`stale visual context fallback was tolerated because refresh was degraded/unavailable ${toleratedStaleFallbacks.length} time(s)`);
  if (entries.some((entry) => entry.type && entry.type !== 'heartbeat' && entry.type !== 'cached-ask')) warnings.push('unknown entry types were ignored');

  return {
    jsonlPath,
    entries: entries.length,
    heartbeatEntries,
    cachedAskEntries,
    captureEntries,
    staleReuses,
    toleratedStaleFallbacks,
    failingStaleReuses,
    refreshDueReuses,
    askRefreshes,
    askFallbacks,
    avgHeartbeatWallMs: avg(heartbeatWallValues),
    avgAskWallMs: avg(askWallValues),
    failures,
    warnings
  };
}

function printReport(report) {
  console.log('Atlas ambient run validation');
  console.log(`- result: ${report.failures.length ? 'FAIL' : 'PASS'}`);
  console.log(`- jsonl: ${report.jsonlPath}`);
  console.log(`- entries: ${report.entries}`);
  console.log(`- heartbeat ticks: ${report.heartbeatEntries.length}`);
  console.log(`- heartbeat captures: ${report.captureEntries.length}`);
  console.log(`- stale reuses: ${report.staleReuses.length}`);
  console.log(`- tolerated stale fallbacks: ${report.toleratedStaleFallbacks.length}`);
  console.log(`- unsafe stale reuses: ${report.failingStaleReuses.length}`);
  console.log(`- refresh-due reuses: ${report.refreshDueReuses.length}`);
  console.log(`- cached asks: ${report.cachedAskEntries.length}`);
  console.log(`- ask-time refreshes: ${report.askRefreshes.length}`);
  console.log(`- avg heartbeat wall: ${formatMs(report.avgHeartbeatWallMs)}`);
  console.log(`- avg ask wall: ${formatMs(report.avgAskWallMs)}`);
  if (report.warnings.length) {
    console.log('');
    console.log('Warnings:');
    for (const warning of report.warnings) console.log(`- ${warning}`);
  }
  if (report.failures.length) {
    console.log('');
    console.log('Failures:');
    for (const failure of report.failures) console.log(`- ${failure}`);
  }
}

async function readJsonlEntries(filePath) {
  if (!existsSync(filePath)) throw new Error(`Ambient JSONL not found: ${filePath}`);
  const text = await readFile(filePath, 'utf8');
  return text.split(/\r?\n/).filter(Boolean).map((line, index) => {
    try {
      return JSON.parse(line);
    } catch (error) {
      throw new Error(`Invalid JSONL at ${filePath}:${index + 1}: ${error instanceof Error ? error.message : String(error)}`);
    }
  });
}

function parseArgs(raw) {
  const parsed = { _: [] };
  for (let index = 0; index < raw.length; index += 1) {
    const arg = raw[index];
    if (arg === '--jsonl') parsed.jsonl = raw[++index];
    else if (arg === '--store') parsed.store = raw[++index];
    else if (arg === '--session') parsed.session = raw[++index];
    else if (arg === '--min-heartbeats') parsed.minHeartbeats = readNonNegativeInteger(raw[++index], '--min-heartbeats');
    else if (arg === '--require-capture') parsed.requireCapture = true;
    else if (arg === '--min-captures') parsed.minCaptures = readNonNegativeInteger(raw[++index], '--min-captures');
    else if (arg === '--require-cached-ask') parsed.requireCachedAsk = true;
    else if (arg === '--min-cached-asks') parsed.minCachedAsks = readNonNegativeInteger(raw[++index], '--min-cached-asks');
    else if (arg === '--max-stale-reuses') parsed.maxStaleReuses = readNonNegativeInteger(raw[++index], '--max-stale-reuses');
    else if (arg === '--fail-on-degraded-stale-reuse') parsed.failOnDegradedStaleReuse = true;
    else if (arg === '--max-refresh-due-reuses') parsed.maxRefreshDueReuses = readNonNegativeInteger(raw[++index], '--max-refresh-due-reuses');
    else if (arg === '--max-ask-refreshes') parsed.maxAskRefreshes = readNonNegativeInteger(raw[++index], '--max-ask-refreshes');
    else if (arg === '--max-ask-wall-ms') parsed.maxAskWallMs = readNonNegativeInteger(raw[++index], '--max-ask-wall-ms');
    else if (arg === '--help' || arg === '-h') {
      console.log(helpText());
      process.exit(0);
    } else {
      parsed._.push(arg);
    }
  }
  return parsed;
}

function resolveAmbientJsonlPath(options) {
  if (options.jsonl) return path.resolve(repoRoot, options.jsonl);
  if (options._[0]) return path.resolve(repoRoot, options._[0]);

  const store = path.resolve(repoRoot, options.store ?? defaultStore);
  const rootJsonl = path.join(store, 'ambient-loop.jsonl');
  if (existsSync(rootJsonl)) return rootJsonl;

  const session = options.session ?? defaultSession;
  return path.join(store, session, 'ambient-loop.jsonl');
}

function isHeartbeatEntry(entry) {
  return entry?.type === 'heartbeat' || entry?.type === undefined;
}

function isToleratedStaleFallback(entry) {
  if (entry.captured || entry.freshness?.stale !== true) return false;
  if (entry.freshness?.signals?.some((signal) => signal === 'high-risk=0.25x')) return false;
  const reason = String(entry.decision?.reason ?? '');
  const cadenceReason = String(entry.cadence?.reason ?? '');
  const signals = Array.isArray(entry.freshness?.signals) ? entry.freshness.signals.join(' ') : '';
  return /refresh is (degraded|unavailable)|refresh path is degraded|fallback is temporary|refresh-(degraded|unavailable)=2x/i.test(
    `${reason} ${cadenceReason} ${signals}`
  );
}

function isFiniteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function readNonNegativeInteger(value, flag) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) throw new Error(`${flag} must be a non-negative integer`);
  return parsed;
}

function avg(values) {
  if (!values.length) return undefined;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function formatMs(value) {
  if (!isFiniteNumber(value)) return 'n/a';
  if (value < 1000) return `${Math.round(value)}ms`;
  return `${(value / 1000).toFixed(2)}s`;
}

function helpText() {
  return `Atlas ambient run validator\n\nUsage:\n  npm run validate:ambient -- [path/to/ambient-loop.jsonl]\n  npm run validate:ambient -- --jsonl path --require-capture --require-cached-ask --max-ask-refreshes 0\n  npm run validate:ambient -- --store .atlas-runs/latest-ambient-android --session live-android-openclaw\n\nDefaults:\n- reads .atlas-runs/latest-ambient-android/live-android-openclaw/ambient-loop.jsonl\n- with --store, reads <store>/ambient-loop.jsonl when present, otherwise <store>/<session>/ambient-loop.jsonl\n- default --session is live-android-openclaw\n- requires at least one heartbeat tick\n- fails on unsafe stale visual context reuse\n- allows stale fallback when refresh is degraded/unavailable and marks it as a warning\n\nUseful inputs:\n  --jsonl <path>\n  --store <path>\n  --session <id>\n\nUseful gates:\n  --min-heartbeats <n>\n  --require-capture | --min-captures <n>\n  --require-cached-ask | --min-cached-asks <n>\n  --max-stale-reuses <n>\n  --fail-on-degraded-stale-reuse\n  --max-refresh-due-reuses <n>\n  --max-ask-refreshes <n>\n  --max-ask-wall-ms <ms>`;
}
