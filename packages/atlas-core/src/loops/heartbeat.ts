import type { AtlasSessionState } from '../types.js';

export type HeartbeatDecision = {
  shouldCapture: boolean;
  shouldCallProvider: boolean;
  reason: string;
};

export function planHeartbeatTick(session: AtlasSessionState, now = Date.now()): HeartbeatDecision {
  if (session.status !== 'active') {
    return { shouldCapture: false, shouldCallProvider: false, reason: 'session is not active' };
  }

  if (!session.permissions.observe || session.permissions.captureImage === 'never') {
    return { shouldCapture: false, shouldCallProvider: false, reason: 'observation is not permitted' };
  }

  const latestObservedAtMs = session.perception.latestObservationAt ? Date.parse(session.perception.latestObservationAt) : undefined;
  const effectiveFreshnessMs = typeof latestObservedAtMs === 'number' && Number.isFinite(latestObservedAtMs)
    ? Math.max(0, now - latestObservedAtMs)
    : session.perception.freshnessMs;
  const stale = effectiveFreshnessMs > 30_000;
  const unstable = session.perception.stability === 'transitioning';
  const refreshHealth = session.perception.health?.visualRefresh;
  const degradedRefresh = refreshHealth?.status === 'degraded' || refreshHealth?.status === 'unavailable';
  const shouldDeferForLatency = stale && degradedRefresh && session.perception.stability !== 'transitioning';

  return {
    shouldCapture: (stale && !shouldDeferForLatency) || unstable,
    shouldCallProvider: false,
    reason: shouldDeferForLatency
      ? `visual context is stale, but refresh is ${refreshHealth?.status}; deferring capture to avoid churn`
      : stale
        ? 'visual context is stale'
        : unstable
          ? 'visual context is unstable'
          : `visual context fresh at ${now}`
  };
}
