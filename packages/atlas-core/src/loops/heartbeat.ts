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
  reason: string;
  signals: string[];
};

export type HeartbeatDecision = {
  shouldCapture: boolean;
  shouldCallProvider: boolean;
  reason: string;
  freshness: HeartbeatFreshnessAssessment;
  cadence: HeartbeatCadenceDecision;
};

export type HeartbeatPolicyOptions = {
  cadence?: Partial<Record<HeartbeatCadenceMode, number>>;
  minDelayMs?: number;
  maxDelayMs?: number;
  baseStaleAfterMs?: number;
  minStaleAfterMs?: number;
  maxStaleAfterMs?: number;
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
  const shouldDeferForLatency = freshness.stale && degradedRefresh && session.perception.stability !== 'transitioning';
  const cadence = planHeartbeatCadence(
    session,
    { stale: freshness.stale, unstable, lowConfidence, moving, highRisk, shouldDeferForLatency },
    options
  );

  return {
    shouldCapture: (freshness.stale && !shouldDeferForLatency) || unstable,
    shouldCallProvider: false,
    freshness,
    cadence,
    reason: shouldDeferForLatency
      ? `visual context is stale (${freshness.reason}), but refresh is ${refreshHealth?.status}; deferring capture to avoid churn`
      : freshness.stale
        ? freshness.reason
        : unstable
          ? 'visual context is unstable'
          : freshness.reason
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
  const stale = !hasVisualContext || contextAgeMs === undefined || contextAgeMs > staleAfterMs;

  return {
    hasVisualContext,
    contextAgeMs,
    baseStaleAfterMs,
    staleAfterMs,
    multiplier,
    stale,
    signals,
    reason: !hasVisualContext
      ? 'no visual context is available yet'
      : contextAgeMs === undefined
        ? 'visual context age is unknown'
        : stale
          ? `visual context age ${formatMs(contextAgeMs)} exceeds stale window ${formatMs(staleAfterMs)}`
          : `visual context age ${formatMs(contextAgeMs)} is within stale window ${formatMs(staleAfterMs)}`
  };
}

type CadenceSignals = {
  stale: boolean;
  unstable: boolean;
  lowConfidence: boolean;
  moving: boolean;
  highRisk: boolean;
  shouldDeferForLatency: boolean;
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

  if (signals.shouldDeferForLatency) {
    return cadenceDecision('stable-scene', 'refresh path is degraded, so slow heartbeat to avoid churn', options);
  }

  if (signals.stale || !session.perception.latestImageId) {
    return cadenceDecision('active-task', signals.stale ? 'visual context is stale' : 'no visual context is available yet', options);
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
