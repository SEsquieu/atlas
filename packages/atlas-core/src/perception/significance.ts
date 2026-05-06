import type { Observation } from '../types.js';

export type SceneSignificanceLevel = 'none' | 'low' | 'meaningful' | 'actionable';

export type SceneSignificanceDecision = {
  level: SceneSignificanceLevel;
  score: number;
  shouldCallProvider: boolean;
  shouldNotifyUser: boolean;
  reason: string;
  signals: {
    summaryDelta?: number;
    sceneDelta?: number;
    motionChanged?: boolean;
    confidenceDrop?: number;
    qualityProblem?: boolean;
    actionableCue?: boolean;
  };
};

export type AssessObservationSignificanceInput = {
  previous?: Observation;
  current: Observation;
};

const ACTIONABLE_CUE_PATTERN = /\b(fire|smoke|flame|hazard|danger|warning|alarm|blood|injur(?:y|ed)|broken glass|spill|leak|collision|crash)\b/i;

export function assessObservationSignificance(input: AssessObservationSignificanceInput): SceneSignificanceDecision {
  const { previous, current } = input;

  if (!previous) {
    return decision('low', 0.1, 'first observation establishes baseline context', {});
  }

  const summaryDelta = textDelta(observationSummary(previous), observationSummary(current));
  const sceneDelta = explicitSceneDelta(current);
  const motionChanged = previous.quality?.motion !== undefined && current.quality?.motion !== undefined && previous.quality.motion !== current.quality.motion;
  const confidenceDrop = confidence(previous) !== undefined && confidence(current) !== undefined
    ? Math.max(0, confidence(previous)! - confidence(current)!)
    : undefined;
  const qualityProblem = Boolean(current.quality?.occluded || current.quality?.lowLight || current.quality?.motion || (current.quality?.blurScore ?? 0) >= 0.75);
  const actionableCue = ACTIONABLE_CUE_PATTERN.test(`${observationSummary(current) ?? ''} ${(current.tags ?? []).join(' ')}`);

  const score = clamp01(Math.max(
    summaryDelta * 0.75,
    sceneDelta ?? 0,
    motionChanged ? 0.35 : 0,
    confidenceDrop ?? 0,
    qualityProblem ? 0.25 : 0,
    actionableCue ? 0.9 : 0
  ));

  const signals = {
    summaryDelta,
    sceneDelta,
    motionChanged: motionChanged || undefined,
    confidenceDrop,
    qualityProblem: qualityProblem || undefined,
    actionableCue: actionableCue || undefined
  };

  if (actionableCue || score >= 0.85) {
    return decision('actionable', score, 'scene appears actionable or safety-relevant', signals);
  }

  if (score >= 0.35) {
    return decision('meaningful', score, 'scene changed enough to justify provider review', signals);
  }

  if (score > 0.05) {
    return decision('low', score, 'only minor scene or quality changes detected', signals);
  }

  return decision('none', score, 'scene appears unchanged', signals);
}

function decision(
  level: SceneSignificanceLevel,
  score: number,
  reason: string,
  signals: SceneSignificanceDecision['signals']
): SceneSignificanceDecision {
  return {
    level,
    score,
    shouldCallProvider: level === 'meaningful' || level === 'actionable',
    shouldNotifyUser: level === 'actionable',
    reason,
    signals
  };
}

function observationSummary(observation: Observation): string | undefined {
  return observation.summary ?? observation.analyses?.find((analysis) => analysis.summary?.trim())?.summary;
}

function confidence(observation: Observation): number | undefined {
  return observation.quality?.confidence ?? observation.analyses?.find((analysis) => typeof analysis.confidence === 'number')?.confidence;
}

function explicitSceneDelta(observation: Observation): number | undefined {
  const analysisDelta = observation.analyses
    ?.filter((analysis) => analysis.kind === 'scene-change')
    .map((analysis) => readDelta(analysis.data) ?? analysis.confidence)
    .find((value) => typeof value === 'number' && Number.isFinite(value));
  return analysisDelta ?? readDelta(observation.data);
}

function readDelta(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return clamp01(value);
  if (typeof value !== 'object' || value === null) return undefined;
  const record = value as Record<string, unknown>;
  const candidates = [record.sceneDelta, record.delta, record.score, record.changeScore];
  const found = candidates.find((candidate) => typeof candidate === 'number' && Number.isFinite(candidate));
  return typeof found === 'number' ? clamp01(found) : undefined;
}

function textDelta(previous: string | undefined, current: string | undefined): number {
  const previousTokens = tokenize(previous);
  const currentTokens = tokenize(current);
  if (previousTokens.size === 0 && currentTokens.size === 0) return 0;
  if (previousTokens.size === 0 || currentTokens.size === 0) return 0.4;

  let intersection = 0;
  for (const token of previousTokens) {
    if (currentTokens.has(token)) intersection += 1;
  }
  const union = new Set([...previousTokens, ...currentTokens]).size;
  return clamp01(1 - intersection / Math.max(1, union));
}

function tokenize(text: string | undefined): Set<string> {
  const stopwords = new Set(['a', 'an', 'and', 'are', 'at', 'in', 'is', 'of', 'on', 'the', 'to', 'with', 'you', 'your']);
  return new Set(
    (text ?? '')
      .toLowerCase()
      .replace(/[^a-z0-9\s-]/g, ' ')
      .split(/\s+/)
      .map((token) => token.trim())
      .filter((token) => token.length > 2 && !stopwords.has(token))
  );
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}
