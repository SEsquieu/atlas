#!/usr/bin/env node
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const args = parseArgs(process.argv.slice(2));
const reportPath = path.resolve(repoRoot, args.report ?? args._[0] ?? path.join('.atlas-runs', 'scenario-harness', 'scenario-report.json'));

const REQUIRED_SCENARIOS = [
  {
    name: 'fresh visual ask captures when context is missing',
    mustHaveEvents: ['user.utterance', 'tool.requested', 'tool.completed', 'observation.captured', 'provider.requested', 'provider.responded', 'agent.speech']
  },
  {
    name: 'fresh stable context reuses cached observation',
    mustHaveEvents: ['user.utterance', 'provider.requested', 'provider.responded', 'agent.speech'],
    maxEventCounts: { 'tool.requested': 0, 'tool.completed': 0 }
  },
  {
    name: 'place confirmation rejects transitional context',
    mustHaveEvents: ['user.utterance', 'tool.requested', 'tool.completed', 'observation.captured', 'provider.requested', 'provider.responded', 'agent.speech']
  },
  {
    name: 'unchanged heartbeat stays silent and avoids provider',
    mustHaveEvents: ['heartbeat.tick', 'tool.requested', 'tool.completed', 'observation.captured', 'perception.significance'],
    maxEventCounts: { 'provider.requested': 0, 'provider.responded': 0, 'agent.speech': 0, 'agent.speech_suppressed': 0 },
    significance: 'none'
  },
  {
    name: 'meaningful heartbeat gets provider review but suppresses speech',
    mustHaveEvents: ['heartbeat.tick', 'observation.captured', 'perception.significance', 'provider.requested', 'provider.responded', 'agent.speech_suppressed'],
    maxEventCounts: { 'agent.speech': 0 },
    significance: 'meaningful'
  },
  {
    name: 'repeated meaningful heartbeat avoids duplicate provider review',
    mustHaveEvents: ['heartbeat.tick', 'observation.captured', 'perception.significance', 'provider.requested', 'provider.responded', 'provider.review_skipped', 'agent.speech_suppressed'],
    maxEventCounts: { 'provider.requested': 1, 'provider.responded': 1, 'provider.review_skipped': 1, 'agent.speech': 0 },
    significance: 'meaningful'
  },
  {
    name: 'actionable heartbeat can escalate when speech is permitted',
    mustHaveEvents: ['heartbeat.tick', 'observation.captured', 'perception.significance', 'provider.requested', 'provider.responded', 'agent.speech'],
    significance: 'actionable'
  },
  {
    name: 'provider swap preserves materialized physical session shape',
    mustHaveEvents: ['user.utterance', 'tool.requested', 'tool.completed', 'observation.captured', 'provider.requested', 'provider.responded', 'agent.speech'],
    latestObservationId: 'provider-swap-image',
    providerTextIncludes: 'provider=alternate-provider'
  },
  {
    name: 'voice output uses bound speaker while preserving text response',
    mustHaveEvents: ['user.utterance', 'provider.requested', 'provider.responded', 'agent.speech', 'audio.speech_requested', 'audio.speech_completed'],
    maxEventCounts: { 'audio.speech_failed': 0 },
    providerTextIncludes: 'Voice output:'
  },
  {
    name: 'voice transcript input routes through normal visual user turn',
    mustHaveEvents: ['audio.transcript_received', 'user.utterance', 'tool.requested', 'tool.completed', 'observation.captured', 'provider.requested', 'provider.responded', 'agent.speech', 'audio.speech_requested', 'audio.speech_completed'],
    maxEventCounts: { 'audio.speech_failed': 0 },
    latestObservationId: 'voice-transcript-view',
    providerTextIncludes: 'Voice transcript:'
  }
];

try {
  const report = await readReport(reportPath);
  const validation = validateReport(report, { strict: args.strict !== false });
  printValidation(validation);
  if (validation.failures.length > 0) process.exitCode = 1;
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}

async function readReport(filePath) {
  if (!existsSync(filePath)) throw new Error(`Scenario report not found: ${filePath}`);
  return JSON.parse(await readFile(filePath, 'utf8'));
}

function validateReport(report, options) {
  const failures = [];
  const warnings = [];

  if (report?.kind !== 'atlas.scenario-report.v1') failures.push(`unexpected report kind: ${report?.kind ?? 'missing'}`);
  if (report?.passed !== true) failures.push('report did not pass');
  if (report?.phoneCameraUsed !== false) failures.push('scenario report should be fake-safe and mark phoneCameraUsed=false');

  const results = Array.isArray(report?.results) ? report.results : [];
  if (!results.length) failures.push('report has no scenario results');
  if (typeof report?.scenarioCount === 'number' && report.scenarioCount !== results.length) {
    failures.push(`scenarioCount=${report.scenarioCount} does not match results length=${results.length}`);
  }

  const byName = new Map(results.map((result) => [result.name, result]));
  for (const rule of REQUIRED_SCENARIOS) {
    const result = byName.get(rule.name);
    if (!result) {
      failures.push(`missing required scenario: ${rule.name}`);
      continue;
    }
    failures.push(...validateScenario(result, rule));
  }

  for (const result of results) {
    if (result.passed !== true) failures.push(`scenario failed: ${result.name ?? '(unnamed)'}`);
    if (!result.audit || typeof result.audit !== 'object') failures.push(`scenario missing audit summary: ${result.name ?? '(unnamed)'}`);
  }

  if (options.strict) {
    const expected = new Set(REQUIRED_SCENARIOS.map((scenario) => scenario.name));
    for (const result of results) {
      if (!expected.has(result.name)) warnings.push(`extra scenario not covered by validator rules: ${result.name}`);
    }
  }

  return {
    reportPath,
    kind: report?.kind,
    passed: report?.passed,
    scenarioCount: results.length,
    failures,
    warnings
  };
}

function validateScenario(result, rule) {
  const failures = [];
  const audit = result.audit ?? {};
  const counts = audit.counts ?? {};
  const eventTypes = Array.isArray(audit.eventTypes) ? audit.eventTypes : [];

  for (const eventType of rule.mustHaveEvents ?? []) {
    if ((counts[eventType] ?? 0) < 1) failures.push(`${rule.name}: expected event ${eventType}`);
  }

  for (const [eventType, maxCount] of Object.entries(rule.maxEventCounts ?? {})) {
    if ((counts[eventType] ?? 0) > maxCount) failures.push(`${rule.name}: expected ${eventType} <= ${maxCount}, found ${counts[eventType]}`);
  }

  if (rule.significance && audit.latestSignificance !== rule.significance) {
    failures.push(`${rule.name}: expected latestSignificance=${rule.significance}, found ${audit.latestSignificance ?? 'missing'}`);
  }

  if (rule.latestObservationId && audit.latestObservationId !== rule.latestObservationId) {
    failures.push(`${rule.name}: expected latestObservationId=${rule.latestObservationId}, found ${audit.latestObservationId ?? 'missing'}`);
  }

  if (rule.providerTextIncludes && !String(audit.latestProviderText ?? '').includes(rule.providerTextIncludes)) {
    failures.push(`${rule.name}: expected latestProviderText to include ${rule.providerTextIncludes}`);
  }

  if (typeof audit.eventCount === 'number' && eventTypes.length !== audit.eventCount) {
    failures.push(`${rule.name}: eventCount=${audit.eventCount} does not match eventTypes length=${eventTypes.length}`);
  }

  return failures;
}

function printValidation(validation) {
  console.log('Atlas scenario report validation');
  console.log(`- result: ${validation.failures.length ? 'FAIL' : 'PASS'}`);
  console.log(`- report: ${validation.reportPath}`);
  console.log(`- kind: ${validation.kind ?? 'missing'}`);
  console.log(`- report passed: ${validation.passed === true ? 'yes' : 'no'}`);
  console.log(`- scenarios: ${validation.scenarioCount}`);

  if (validation.warnings.length) {
    console.log('');
    console.log('Warnings:');
    for (const warning of validation.warnings) console.log(`- ${warning}`);
  }

  if (validation.failures.length) {
    console.log('');
    console.log('Failures:');
    for (const failure of validation.failures) console.log(`- ${failure}`);
  }
}

function parseArgs(raw) {
  const parsed = { _: [] };
  for (let index = 0; index < raw.length; index += 1) {
    const arg = raw[index];
    if (arg === '--report') parsed.report = raw[++index];
    else if (arg === '--no-strict') parsed.strict = false;
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
  return `Atlas scenario report validator\n\nUsage:\n  npm run validate:scenarios -- [path/to/scenario-report.json]\n  npm run validate:scenarios -- --report .atlas-runs/scenario-harness/scenario-report.json\n\nValidates that the fake-safe scenario report contains the required scenarios and expected event patterns. Fails with nonzero exit on missing scenarios, failed report status, stale/mismatched report shape, or semantic event regressions. Use --no-strict to suppress warnings about extra scenarios not covered by validator rules.`;
}
