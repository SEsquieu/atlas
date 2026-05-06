import type { AtlasSessionState, MotionState } from '../types.js';

export type HeartbeatCadenceMode = 'idle' | 'stable-scene' | 'active-task' | 'unstable-scene' | 'high-risk';

export type HeartbeatCadenceDecision = {
  mode: HeartbeatCadenceMode;
  nextDelayMs: number;
  reason: string;
};

export type HeartbeatDecision = {
  shouldCapture: boolean;
  shouldCallProvider: boolean;
  reason: string;
  cadence: HeartbeatCadenceDecision;
};

export type HeartbeatPolicyOptions = {
  cadence?: Partial<Record<HeartbeatCadenceMode, number>>;
  minDelayMs?: number;
  maxDelayMs?: number;
};

const DEFAULT_STALE_AFTER_MS = 30_000;
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
      cadence: cadenceDecision('idle', 'session is not active', options)
    };
  }

  if (!session.permissions.observe || session.permissions.captureImage === 'never') {
    return {
      shouldCapture: false,
      shouldCallProvider: false,
      reason: 'observation is not permitted',
      cadence: cadenceDecision('idle', 'observation is not permitted', options)
    };
  }

  const latestObservedAtMs = session.perception.latestObservationAt ? Date.parse(session.perception.latestObservationAt) : undefined;
  const effectiveFreshnessMs = typeof latestObservedAtMs === 'number' && Number.isFinite(latestObservedAtMs)
    ? Math.max(0, now - latestObservedAtMs)
    : session.perception.freshnessMs;
  const stale = effectiveFreshnessMs > DEFAULT_STALE_AFTER_MS;
  const hasVisualContext = Boolean(session.perception.latestImageId);
  const unstable = session.perception.stability === 'transitioning';
  const lowConfidence = hasVisualContext && session.perception.confidence < 0.5;
  const moving = session.perception.motionState ? UNSTABLE_MOTION_STATES.has(session.perception.motionState) : false;
  const highRisk = (session.perception.relevance?.['high-risk'] ?? 0) >= 0.75;
  const refreshHealth = session.perception.health?.visualRefresh;
  const degradedRefresh = refreshHealth?.status === 'degraded' || refreshHealth?.status === 'unavailable';
  const shouldDeferForLatency = stale && degradedRefresh && session.perception.stability !== 'transitioning';
  const cadence = planHeartbeatCadence(
    session,
    { stale, unstable, lowConfidence, moving, highRisk, shouldDeferForLatency },
    options
  );

  return {
    shouldCapture: (stale && !shouldDeferForLatency) || unstable,
    shouldCallProvider: false,
    cadence,
    reason: shouldDeferForLatency
      ? `visual context is stale, but refresh is ${refreshHealth?.status}; deferring capture to avoid churn`
      : stale
        ? 'visual context is stale'
        : unstable
          ? 'visual context is unstable'
          : `visual context fresh at ${now}`
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

function finiteOrUndefined(value: number | undefined): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}
