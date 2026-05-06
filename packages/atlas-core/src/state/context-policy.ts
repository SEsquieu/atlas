import type { ContextStatus, LatencyHealthStatus, MotionState } from '../types.js';

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
export type VisualFreshnessDecision = 'reuse' | 'background-refresh' | 'degraded-reuse' | 'refresh';

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
  latencyHealth: LatencyHealthStatus;
  latencyMs?: number;
  reasons: string[];
};

export const DEFAULT_REFRESH_POLICY: RefreshPolicy = {
  maxAgeMs: 15_000,
  minConfidence: 0.65
};

export const VISUAL_REFRESH_LATENCY_BANDS_MS = {
  healthy: 10_000,
  slow: 30_000,
  degraded: 120_000
} as const;

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

export function classifyVisualRefreshLatency(latencyMs: number | undefined): LatencyHealthStatus {
  if (typeof latencyMs !== 'number' || !Number.isFinite(latencyMs)) return 'healthy';
  if (latencyMs <= VISUAL_REFRESH_LATENCY_BANDS_MS.healthy) return 'healthy';
  if (latencyMs <= VISUAL_REFRESH_LATENCY_BANDS_MS.slow) return 'slow';
  if (latencyMs <= VISUAL_REFRESH_LATENCY_BANDS_MS.degraded) return 'degraded';
  return 'unavailable';
}

export function evaluateVisualContextFreshness(
  status: ContextStatus | undefined,
  options: { text?: string; useCase?: VisualContextUseCase; policy?: RefreshPolicy } = {}
): VisualFreshnessAssessment {
  const useCase = options.useCase ?? classifyVisualContextUseCase(options.text ?? '');
  const motionState = status?.motionState ?? 'unknown';
  const latencyMs = status?.refreshHealth?.analysisLatencyMs ?? status?.analysisLatencyMs ?? status?.refreshHealth?.latencyMs ?? status?.latencyMs;
  const latencyHealth = status?.refreshHealth?.status ?? classifyVisualRefreshLatency(latencyMs);
  const maxUsableAgeMs = Math.min(
    MAX_VISUAL_AGE_MS[motionState][useCase],
    options.policy?.maxAgeMs ?? Number.MAX_SAFE_INTEGER
  );
  const minConfidence = options.policy?.minConfidence ?? DEFAULT_REFRESH_POLICY.minConfidence;
  const reasons: string[] = [];

  if (!status?.available) {
    return { decision: 'refresh', score: 0, useCase, motionState, maxUsableAgeMs, latencyHealth, latencyMs, reasons: ['visual context unavailable'] };
  }

  if (status.relevant === false) reasons.push('visual context marked irrelevant');
  if (status.stability === 'transitioning') reasons.push(`visual context is ${status.stability}`);
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
    if (canDeferRefreshForLatency({ status, useCase, latencyHealth, ageMs, maxUsableAgeMs, reasons })) {
      return {
        decision: 'degraded-reuse',
        score: 0.35,
        useCase,
        motionState,
        maxUsableAgeMs,
        ageMs,
        latencyHealth,
        latencyMs,
        reasons: [...reasons, `visual refresh path is ${latencyHealth}; avoid immediate refresh churn`]
      };
    }
    return { decision: 'refresh', score: 0, useCase, motionState, maxUsableAgeMs, ageMs, latencyHealth, latencyMs, reasons };
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
    latencyHealth,
    latencyMs,
    reasons: score < 0.75 ? ['visual context usable but decaying'] : ['visual context fresh enough to reuse']
  };
}

function canDeferRefreshForLatency(input: {
  status: ContextStatus;
  useCase: VisualContextUseCase;
  latencyHealth: LatencyHealthStatus;
  ageMs?: number;
  maxUsableAgeMs: number;
  reasons: string[];
}): boolean {
  if (input.latencyHealth !== 'degraded' && input.latencyHealth !== 'unavailable') return false;
  if (input.useCase === 'high-risk' || input.useCase === 'navigation') return false;
  if (input.status.available !== true || input.status.relevant === false) return false;
  if (input.status.stability === 'transitioning') return false;
  if (typeof input.status.confidence === 'number' && input.status.confidence < DEFAULT_REFRESH_POLICY.minConfidence) return false;
  if (!input.reasons.some((reason) => reason.startsWith('visual context too old'))) return false;
  if (typeof input.ageMs !== 'number') return false;

  const latencyMs = input.status.refreshHealth?.analysisLatencyMs ?? input.status.analysisLatencyMs ?? input.status.refreshHealth?.latencyMs ?? input.status.latencyMs ?? 0;
  const degradedAllowanceMs = Math.max(input.maxUsableAgeMs * 2, input.maxUsableAgeMs + latencyMs);
  return input.ageMs <= degradedAllowanceMs;
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
