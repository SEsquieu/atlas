import type { CaptureBudgetDecision } from './capture-budget.js';
import type { AtlasSessionState, MotionState } from '../types.js';

export type HeartbeatCadenceMode = 'idle' | 'stable-scene' | 'active-task' | 'unstable-scene' | 'high-risk';

export type HeartbeatCadenceDecision = {
  mode: HeartbeatCadenceMode;
  nextDelayMs: number;
  reason: string;
};

export type HeartbeatFreshnessAssessment = {
  hasVisualContext: boolean;
  contextAgeMs?: number;
  baseStaleAfterMs: number;
  staleAfterMs: number;
  multiplier: number;
  stale: boolean;
  refreshDue: boolean;
  staleAtMs?: number;
  refreshDueAtMs?: number;
  expectedRefreshLatencyMs: number;
  safetyMarginMs: number;
  reason: string;
  signals: string[];
};

export type HeartbeatDecision = {
  shouldCapture: boolean;
  shouldCallProvider: boolean;
  reason: string;
  freshness: HeartbeatFreshnessAssessment;
  captureBudget?: CaptureBudgetDecision;
  cadence: HeartbeatCadenceDecision;
  fallback?: HeartbeatFallbackDecision;
};

export type HeartbeatFallbackDecision = {
  kind: 'refresh-deferred';
  reason: string;
  refreshHealth: 'degraded' | 'unavailable';
  retryable: true;
  retryAfterMs: number;
  retryDue: boolean;
};

export type HeartbeatPolicyOptions = {
  cadence?: Partial<Record<HeartbeatCadenceMode, number>>;
  minDelayMs?: number;
  maxDelayMs?: number;
  baseStaleAfterMs?: number;
  minStaleAfterMs?: number;
  maxStaleAfterMs?: number;
  expectedRefreshLatencyMs?: number;
  minExpectedRefreshLatencyMs?: number;
  maxExpectedRefreshLatencyMs?: number;
  refreshSafetyMarginMs?: number;
  refreshFailureRetryMs?: number;
  captureBudget?: CaptureBudgetDecision;
};

const DEFAULT_STALE_AFTER_MS = 30_000;
const DEFAULT_MIN_STALE_AFTER_MS = 5_000;
const DEFAULT_MAX_STALE_AFTER_MS = 2 * 60_000;
const DEFAULT_CADENCE_MS: Record<HeartbeatCadenceMode, number> = {
  idle: 5 * 60_000,
  'stable-scene': 60_000,
  'active-task': 30_000,
  'unstable-scene': 10_000,
  'high-risk': 5_000
};
const DEFAULT_EXPECTED_REFRESH_LATENCY_MS = 10_000;
const DEFAULT_MIN_EXPECTED_REFRESH_LATENCY_MS = 1_000;
const DEFAULT_MAX_EXPECTED_REFRESH_LATENCY_MS = 30_000;
const DEFAULT_REFRESH_SAFETY_MARGIN_MS = 2_000;
const DEFAULT_REFRESH_FAILURE_RETRY_MS = 2 * 60_000;

const UNSTABLE_MOTION_STATES = new Set<MotionState>(['turning', 'walking', 'vehicle']);

export function planHeartbeatTick(session: AtlasSessionState, now = Date.now(), options: HeartbeatPolicyOptions = {}): HeartbeatDecision {
  if (session.status !== 'active') {
    return {
      shouldCapture: false,
      shouldCallProvider: false,
      reason: 'session is not active',
      freshness: inactiveFreshnessAssessment(session, options, false),
      cadence: cadenceDecision('idle', 'session is not active', options)
    };
  }

  if (!session.permissions.observe || session.permissions.captureImage === 'never') {
    return {
      shouldCapture: false,
      shouldCallProvider: false,
      reason: 'observation is not permitted',
      freshness: inactiveFreshnessAssessment(session, options, false),
      cadence: cadenceDecision('idle', 'observation is not permitted', options)
    };
  }

  const highRisk = (session.perception.relevance?.['high-risk'] ?? 0) >= 0.75;
  const freshness = assessHeartbeatFreshness(session, now, options, { highRisk });
  const hasVisualContext = freshness.hasVisualContext;
  const unstable = session.perception.stability === 'transitioning';
  const lowConfidence = hasVisualContext && session.perception.confidence < 0.5;
  const moving = session.perception.motionState ? UNSTABLE_MOTION_STATES.has(session.perception.motionState) : false;
  const refreshHealth = session.perception.health?.visualRefresh;
  const degradedRefresh = refreshHealth?.status === 'degraded' || refreshHealth?.status === 'unavailable';
  const needsRefresh = freshness.stale || freshness.refreshDue;
  const fallback = degradedRefresh
    ? buildRefreshFallbackDecision(refreshHealth, now, options)
    : undefined;
  const shouldDeferForLatency = Boolean(
    needsRefresh &&
      hasVisualContext &&
      fallback &&
      !fallback.retryDue &&
      !highRisk &&
      !unstable &&
      !moving &&
      !lowConfidence
  );
  const initialShouldCapture = (needsRefresh && !shouldDeferForLatency) || unstable;
  const budgetDeferReason = budgetDeferCaptureReason(options.captureBudget, {
    shouldCapture: initialShouldCapture,
    highRisk,
    unstable,
    hasVisualContext
  });
  const cadence = capCadenceToRefreshDeadline(
    planHeartbeatCadence(
      session,
      {
        stale: freshness.stale,
        refreshDue: freshness.refreshDue,
        unstable,
        lowConfidence,
        moving,
        highRisk,
        shouldDeferForLatency,
        budgetDeferred: Boolean(budgetDeferReason),
        fallback
      },
      options
    ),
    freshness,
    now
  );

  return {
    shouldCapture: initialShouldCapture && !budgetDeferReason,
    shouldCallProvider: false,
    freshness,
    captureBudget: options.captureBudget,
    cadence,
    fallback: shouldDeferForLatency ? fallback : undefined,
    reason: budgetDeferReason ?? (shouldDeferForLatency
      ? `visual context needs refresh (${freshness.reason}), but refresh is ${refreshHealth?.status}; using degraded fallback temporarily and retrying after ${formatMs(fallback?.retryAfterMs ?? 0)}`
      : freshness.stale || freshness.refreshDue
        ? fallback?.retryDue
          ? `${freshness.reason}; refresh fallback retry window elapsed, attempting a new capture`
          : freshness.reason
        : unstable
          ? 'visual context is unstable'
          : freshness.reason)
  };
}

export function assessHeartbeatFreshness(
  session: AtlasSessionState,
  now = Date.now(),
  options: HeartbeatPolicyOptions = {},
  input: { highRisk?: boolean } = {}
): HeartbeatFreshnessAssessment {
  const hasVisualContext = Boolean(session.perception.latestImageId);
  const baseStaleAfterMs = finiteOrUndefined(options.baseStaleAfterMs) ?? DEFAULT_STALE_AFTER_MS;
  const latestObservedAtMs = session.perception.latestObservationAt ? Date.parse(session.perception.latestObservationAt) : undefined;
  const rawContextAgeMs = typeof latestObservedAtMs === 'number' && Number.isFinite(latestObservedAtMs)
    ? Math.max(0, now - latestObservedAtMs)
    : finiteOrUndefined(session.perception.freshnessMs);
  const contextAgeMs = hasVisualContext ? rawContextAgeMs : undefined;
  const { multiplier, signals } = hasVisualContext
    ? freshnessMultiplier(session, { highRisk: input.highRisk === true })
    : { multiplier: 1, signals: [] };
  const staleAfterMs = clampStaleAfter(baseStaleAfterMs * multiplier, options);
  const expectedRefreshLatencyMs = expectedRefreshLatency(session, options);
  const safetyMarginMs = finiteOrUndefined(options.refreshSafetyMarginMs) ?? DEFAULT_REFRESH_SAFETY_MARGIN_MS;
  const stale = !hasVisualContext || contextAgeMs === undefined || contextAgeMs > staleAfterMs;
  const staleAtMs = hasVisualContext && typeof latestObservedAtMs === 'number' && Number.isFinite(latestObservedAtMs)
    ? latestObservedAtMs + staleAfterMs
    : undefined;
  const refreshLeadMs = Math.max(0, expectedRefreshLatencyMs + Math.max(0, safetyMarginMs));
  const refreshDueAtMs = typeof staleAtMs === 'number' ? Math.max(latestObservedAtMs ?? 0, staleAtMs - refreshLeadMs) : undefined;
  const refreshDue = hasVisualContext && !stale && typeof refreshDueAtMs === 'number' && now >= refreshDueAtMs;

  return {
    hasVisualContext,
    contextAgeMs,
    baseStaleAfterMs,
    staleAfterMs,
    multiplier,
    stale,
    refreshDue,
    staleAtMs,
    refreshDueAtMs,
    expectedRefreshLatencyMs,
    safetyMarginMs,
    signals,
    reason: !hasVisualContext
      ? 'no visual context is available yet'
      : contextAgeMs === undefined
        ? 'visual context age is unknown'
        : stale
          ? `visual context age ${formatMs(contextAgeMs)} exceeds stale window ${formatMs(staleAfterMs)}`
          : refreshDue
            ? `visual context age ${formatMs(contextAgeMs)} is within stale window ${formatMs(staleAfterMs)} but refresh is due before stale deadline`
            : `visual context age ${formatMs(contextAgeMs)} is within stale window ${formatMs(staleAfterMs)}`
  };
}

type CadenceSignals = {
  stale: boolean;
  refreshDue: boolean;
  unstable: boolean;
  lowConfidence: boolean;
  moving: boolean;
  highRisk: boolean;
  shouldDeferForLatency: boolean;
  budgetDeferred: boolean;
  fallback?: HeartbeatFallbackDecision;
};

function planHeartbeatCadence(
  session: AtlasSessionState,
  signals: CadenceSignals,
  options: HeartbeatPolicyOptions
): HeartbeatCadenceDecision {
  if (signals.highRisk) {
    return cadenceDecision('high-risk', 'high-risk perception relevance requests the fastest allowed heartbeat', options);
  }

  if (signals.unstable || signals.moving || signals.lowConfidence) {
    const reasons = [
      signals.unstable ? 'scene is transitioning' : undefined,
      signals.moving ? `motion state is ${session.perception.motionState}` : undefined,
      signals.lowConfidence ? 'visual confidence is low' : undefined
    ].filter(Boolean);
    return cadenceDecision('unstable-scene', reasons.join('; '), options);
  }

  if (signals.budgetDeferred) {
    return cadenceDecision('stable-scene', 'capture budget is constrained, so slow heartbeat to avoid device churn', options);
  }

  if (signals.shouldDeferForLatency) {
    return customCadenceDecision(
      'active-task',
      signals.fallback?.retryAfterMs ?? DEFAULT_CADENCE_MS['active-task'],
      'refresh path is degraded; fallback is temporary, not accepted as stable context',
      options
    );
  }

  if (signals.stale || signals.refreshDue || !session.perception.latestImageId) {
    return cadenceDecision(
      'active-task',
      signals.stale ? 'visual context is stale' : signals.refreshDue ? 'visual context refresh is due before stale deadline' : 'no visual context is available yet',
      options
    );
  }

  return cadenceDecision('stable-scene', 'scene is stable with reusable visual context', options);
}

function freshnessMultiplier(session: AtlasSessionState, input: { highRisk: boolean }): { multiplier: number; signals: string[] } {
  let multiplier = 1;
  const signals: string[] = [];

  if (input.highRisk) {
    multiplier *= 0.25;
    signals.push('high-risk=0.25x');
  }

  if (session.perception.stability === 'transitioning') {
    multiplier *= 0.25;
    signals.push('transitioning=0.25x');
  } else if (session.perception.stability === 'stable' && session.perception.confidence >= 0.85) {
    multiplier *= 1.5;
    signals.push('stable-high-confidence=1.5x');
  }

  switch (session.perception.motionState) {
    case 'walking':
    case 'turning':
      multiplier *= 0.33;
      signals.push(`${session.perception.motionState}=0.33x`);
      break;
    case 'vehicle':
      multiplier *= 0.25;
      signals.push('vehicle=0.25x');
      break;
    case 'handheld-stable':
      multiplier *= 0.75;
      signals.push('handheld-stable=0.75x');
      break;
    case 'stationary':
      multiplier *= 1.25;
      signals.push('stationary=1.25x');
      break;
  }

  if (session.perception.confidence < 0.5) {
    multiplier *= 0.5;
    signals.push('low-confidence=0.5x');
  } else if (session.perception.confidence < 0.75) {
    multiplier *= 0.75;
    signals.push('medium-confidence=0.75x');
  }

  const refreshHealth = session.perception.health?.visualRefresh;
  const degradedRefresh = refreshHealth?.status === 'degraded' || refreshHealth?.status === 'unavailable';
  if (degradedRefresh && session.perception.stability === 'stable') {
    multiplier *= 2;
    signals.push(`refresh-${refreshHealth?.status}=2x`);
  }

  return { multiplier: roundMultiplier(multiplier), signals };
}

function capCadenceToRefreshDeadline(
  cadence: HeartbeatCadenceDecision,
  freshness: HeartbeatFreshnessAssessment,
  now: number
): HeartbeatCadenceDecision {
  if (freshness.stale || freshness.refreshDue || typeof freshness.refreshDueAtMs !== 'number') return cadence;
  const msUntilRefreshDue = Math.max(0, Math.ceil(freshness.refreshDueAtMs - now));
  if (msUntilRefreshDue >= cadence.nextDelayMs) return cadence;
  return {
    ...cadence,
    nextDelayMs: msUntilRefreshDue,
    reason: `${cadence.reason}; capped to refresh deadline in ${formatMs(msUntilRefreshDue)}`
  };
}

function expectedRefreshLatency(session: AtlasSessionState, options: HeartbeatPolicyOptions): number {
  const configured = finiteOrUndefined(options.expectedRefreshLatencyMs);
  const observed = finiteOrUndefined(session.perception.observationLatencyMs);
  const refreshHealthLatency = finiteOrUndefined(session.perception.health?.visualRefresh?.latencyMs);
  const analysisLatency = finiteOrUndefined(session.perception.analysisLatencyMs) ?? finiteOrUndefined(session.perception.health?.visualRefresh?.analysisLatencyMs);
  const raw = configured ?? observed ?? refreshHealthLatency ?? analysisLatency ?? DEFAULT_EXPECTED_REFRESH_LATENCY_MS;
  const min = finiteOrUndefined(options.minExpectedRefreshLatencyMs) ?? DEFAULT_MIN_EXPECTED_REFRESH_LATENCY_MS;
  const max = finiteOrUndefined(options.maxExpectedRefreshLatencyMs) ?? DEFAULT_MAX_EXPECTED_REFRESH_LATENCY_MS;
  return Math.min(Math.max(raw, min), max);
}

function budgetDeferCaptureReason(
  budget: CaptureBudgetDecision | undefined,
  signals: { shouldCapture: boolean; highRisk: boolean; unstable: boolean; hasVisualContext: boolean }
): string | undefined {
  if (!budget || !signals.shouldCapture) return undefined;
  if (budget.status === 'cooldown') {
    return `capture budget is in cooldown (${budget.reason}); deferring heartbeat capture until ${budget.cooldownUntil ?? 'later'}`;
  }
  if (budget.status === 'constrained' && !signals.highRisk && !signals.unstable && signals.hasVisualContext) {
    return `capture budget is constrained (${budget.reason}); reusing stale context instead of adding device pressure`;
  }
  return undefined;
}

function buildRefreshFallbackDecision(
  refreshHealth: NonNullable<AtlasSessionState['perception']['health']>['visualRefresh'],
  now: number,
  options: HeartbeatPolicyOptions
): HeartbeatFallbackDecision | undefined {
  if (!refreshHealth || (refreshHealth.status !== 'degraded' && refreshHealth.status !== 'unavailable')) return undefined;
  const retryWindowMs = finiteOrUndefined(options.refreshFailureRetryMs) ?? DEFAULT_REFRESH_FAILURE_RETRY_MS;
  const sinceMs = refreshHealth.since ? Date.parse(refreshHealth.since) : undefined;
  const ageMs = typeof sinceMs === 'number' && Number.isFinite(sinceMs) ? Math.max(0, now - sinceMs) : undefined;
  const retryAfterMs = ageMs === undefined ? 0 : Math.max(0, retryWindowMs - ageMs);
  return {
    kind: 'refresh-deferred',
    reason: refreshHealth.reason ?? `visual refresh is ${refreshHealth.status}`,
    refreshHealth: refreshHealth.status,
    retryable: true,
    retryAfterMs,
    retryDue: retryAfterMs === 0
  };
}

function inactiveFreshnessAssessment(
  session: AtlasSessionState,
  options: HeartbeatPolicyOptions,
  stale: boolean
): HeartbeatFreshnessAssessment {
  const baseStaleAfterMs = finiteOrUndefined(options.baseStaleAfterMs) ?? DEFAULT_STALE_AFTER_MS;
  const staleAfterMs = clampStaleAfter(baseStaleAfterMs, options);
  return {
    hasVisualContext: Boolean(session.perception.latestImageId),
    contextAgeMs: finiteOrUndefined(session.perception.freshnessMs),
    baseStaleAfterMs,
    staleAfterMs,
    multiplier: 1,
    stale,
    refreshDue: false,
    expectedRefreshLatencyMs: expectedRefreshLatency(session, options),
    safetyMarginMs: finiteOrUndefined(options.refreshSafetyMarginMs) ?? DEFAULT_REFRESH_SAFETY_MARGIN_MS,
    reason: 'heartbeat freshness was not assessed because the heartbeat is inactive',
    signals: []
  };
}

function cadenceDecision(mode: HeartbeatCadenceMode, reason: string, options: HeartbeatPolicyOptions): HeartbeatCadenceDecision {
  const configured = options.cadence?.[mode];
  const rawDelay = typeof configured === 'number' && Number.isFinite(configured) ? configured : DEFAULT_CADENCE_MS[mode];
  const nextDelayMs = clampDelay(rawDelay, options);
  return { mode, nextDelayMs, reason };
}

function customCadenceDecision(mode: HeartbeatCadenceMode, rawDelay: number, reason: string, options: HeartbeatPolicyOptions): HeartbeatCadenceDecision {
  return { mode, nextDelayMs: clampDelay(rawDelay, options), reason };
}

function clampDelay(delayMs: number, options: HeartbeatPolicyOptions): number {
  const minDelayMs = finiteOrUndefined(options.minDelayMs);
  const maxDelayMs = finiteOrUndefined(options.maxDelayMs);
  let next = Math.max(0, delayMs);
  if (minDelayMs !== undefined) next = Math.max(minDelayMs, next);
  if (maxDelayMs !== undefined) next = Math.min(maxDelayMs, next);
  return next;
}

function clampStaleAfter(staleAfterMs: number, options: HeartbeatPolicyOptions): number {
  const minStaleAfterMs = finiteOrUndefined(options.minStaleAfterMs) ?? DEFAULT_MIN_STALE_AFTER_MS;
  const maxStaleAfterMs = finiteOrUndefined(options.maxStaleAfterMs) ?? DEFAULT_MAX_STALE_AFTER_MS;
  return Math.max(minStaleAfterMs, Math.min(maxStaleAfterMs, Math.max(0, staleAfterMs)));
}

function roundMultiplier(value: number): number {
  return Math.round(value * 1000) / 1000;
}

function formatMs(value: number): string {
  if (value < 1000) return `${Math.round(value)}ms`;
  return `${(value / 1000).toFixed(2)}s`;
}

function finiteOrUndefined(value: number | undefined): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}
