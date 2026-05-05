import type { ContextStatus, MotionState } from '../types.js';

const VISUAL_INTENT_PATTERNS = [
  /what am i looking at/i,
  /am i (in )?the right place/i,
  /is this (the )?right/i,
  /which one/i,
  /where should i go/i,
  /read this/i,
  /what does this say/i,
  /can you see/i,
  /help me fix/i,
  /do i have everything/i
];

const HIGH_RISK_PATTERNS = [/wire should i cut/i, /should i cut/i, /is it safe/i, /safety/i, /danger/i, /hot wire/i, /drive/i];
const NAVIGATION_PATTERNS = [/where should i go/i, /which (aisle|shelf|direction|way)/i, /am i (at|near|in) the right/i, /right shelf/i, /right place/i];
const CONFIRMATION_PATTERNS = [/^\s*(is|are|am|do|does|can)\b/i, /confirm/i, /right one/i, /do i have everything/i];

export type VisualContextUseCase = 'descriptive' | 'confirmation' | 'navigation' | 'high-risk';
export type VisualFreshnessDecision = 'reuse' | 'background-refresh' | 'refresh';

export type RefreshPolicy = {
  maxAgeMs: number;
  minConfidence: number;
};

export type VisualFreshnessAssessment = {
  decision: VisualFreshnessDecision;
  score: number;
  useCase: VisualContextUseCase;
  motionState: MotionState;
  maxUsableAgeMs: number;
  ageMs?: number;
  reasons: string[];
};

export const DEFAULT_REFRESH_POLICY: RefreshPolicy = {
  maxAgeMs: 15_000,
  minConfidence: 0.65
};

const MAX_VISUAL_AGE_MS: Record<MotionState, Record<VisualContextUseCase, number>> = {
  stationary: {
    descriptive: 120_000,
    confirmation: 45_000,
    navigation: 30_000,
    'high-risk': 0
  },
  'handheld-stable': {
    descriptive: 60_000,
    confirmation: 30_000,
    navigation: 15_000,
    'high-risk': 0
  },
  turning: {
    descriptive: 15_000,
    confirmation: 8_000,
    navigation: 5_000,
    'high-risk': 0
  },
  walking: {
    descriptive: 8_000,
    confirmation: 5_000,
    navigation: 3_000,
    'high-risk': 0
  },
  vehicle: {
    descriptive: 5_000,
    confirmation: 3_000,
    navigation: 1_000,
    'high-risk': 0
  },
  unknown: {
    descriptive: 15_000,
    confirmation: 15_000,
    navigation: 10_000,
    'high-risk': 0
  }
};

export function promptLikelyNeedsVisualContext(text: string): boolean {
  return VISUAL_INTENT_PATTERNS.some((pattern) => pattern.test(text));
}

export function classifyVisualContextUseCase(text: string): VisualContextUseCase {
  if (HIGH_RISK_PATTERNS.some((pattern) => pattern.test(text))) return 'high-risk';
  if (NAVIGATION_PATTERNS.some((pattern) => pattern.test(text))) return 'navigation';
  if (CONFIRMATION_PATTERNS.some((pattern) => pattern.test(text))) return 'confirmation';
  return 'descriptive';
}

export function evaluateVisualContextFreshness(
  status: ContextStatus | undefined,
  options: { text?: string; useCase?: VisualContextUseCase; policy?: RefreshPolicy } = {}
): VisualFreshnessAssessment {
  const useCase = options.useCase ?? classifyVisualContextUseCase(options.text ?? '');
  const motionState = status?.motionState ?? 'unknown';
  const maxUsableAgeMs = Math.min(
    MAX_VISUAL_AGE_MS[motionState][useCase],
    options.policy?.maxAgeMs ?? Number.MAX_SAFE_INTEGER
  );
  const minConfidence = options.policy?.minConfidence ?? DEFAULT_REFRESH_POLICY.minConfidence;
  const reasons: string[] = [];

  if (!status?.available) {
    return { decision: 'refresh', score: 0, useCase, motionState, maxUsableAgeMs, reasons: ['visual context unavailable'] };
  }

  if (status.relevant === false) reasons.push('visual context marked irrelevant');
  if (status.stability && status.stability !== 'stable') reasons.push(`visual context is ${status.stability}`);
  if (useCase === 'high-risk') reasons.push('high-risk visual confirmation requires fresh capture');

  const ageMs = status.ageMs;
  if (typeof ageMs !== 'number') reasons.push('visual context age unknown');
  if (typeof status.confidence === 'number' && status.confidence < minConfidence) {
    reasons.push(`confidence below threshold (${status.confidence} < ${minConfidence})`);
  }
  if (typeof ageMs === 'number' && maxUsableAgeMs === 0) reasons.push('context cannot be reused for this use case');
  if (typeof ageMs === 'number' && maxUsableAgeMs > 0 && ageMs > maxUsableAgeMs) {
    reasons.push(`visual context too old (${ageMs}ms > ${maxUsableAgeMs}ms)`);
  }

  if (reasons.length > 0) {
    return { decision: 'refresh', score: 0, useCase, motionState, maxUsableAgeMs, ageMs, reasons };
  }

  const ageScore = typeof ageMs === 'number' && maxUsableAgeMs > 0 ? Math.max(0, 1 - ageMs / maxUsableAgeMs) : 0.5;
  const confidenceScore = typeof status.confidence === 'number' ? Math.max(0, Math.min(1, status.confidence)) : 0.75;
  const motionPenalty = motionState === 'unknown' ? 0.9 : 1;
  const score = roundScore(Math.min(ageScore, confidenceScore) * motionPenalty);

  return {
    decision: score < 0.4 ? 'refresh' : score < 0.75 ? 'background-refresh' : 'reuse',
    score,
    useCase,
    motionState,
    maxUsableAgeMs,
    ageMs,
    reasons: score < 0.75 ? ['visual context usable but decaying'] : ['visual context fresh enough to reuse']
  };
}

export function shouldRefreshContext(
  status: ContextStatus | undefined,
  policy: RefreshPolicy = DEFAULT_REFRESH_POLICY
): boolean {
  return evaluateVisualContextFreshness(status, { policy, useCase: 'descriptive' }).decision === 'refresh';
}

function roundScore(value: number): number {
  return Math.round(value * 1000) / 1000;
}
