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

  const stale = session.perception.freshnessMs > 30_000;
  const unstable = session.perception.stability !== 'stable';

  return {
    shouldCapture: stale || unstable,
    shouldCallProvider: false,
    reason: stale ? 'visual context is stale' : unstable ? 'visual context is unstable' : `visual context fresh at ${now}`
  };
}
