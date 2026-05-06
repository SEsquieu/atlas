import type { AuditEvent } from '../audit/event-log.js';

export type CaptureBudgetStatus = 'healthy' | 'warm' | 'constrained' | 'cooldown';

export type CaptureBudgetDecision = {
  status: CaptureBudgetStatus;
  capturesLastMinute: number;
  capturesLastFiveMinutes: number;
  averageCaptureLatencyMs?: number;
  cooldownUntil?: string;
  reason: string;
};

export type CaptureBudgetOptions = {
  warmPerMinute?: number;
  constrainedPerMinute?: number;
  cooldownPerMinute?: number;
  constrainedPerFiveMinutes?: number;
  cooldownPerFiveMinutes?: number;
  warmLatencyMs?: number;
  constrainedLatencyMs?: number;
  cooldownMs?: number;
};

const DEFAULTS = {
  warmPerMinute: 3,
  constrainedPerMinute: 5,
  cooldownPerMinute: 8,
  constrainedPerFiveMinutes: 10,
  cooldownPerFiveMinutes: 20,
  warmLatencyMs: 15_000,
  constrainedLatencyMs: 30_000,
  cooldownMs: 60_000
};

export function assessCaptureBudget(events: AuditEvent[], now = Date.now(), options: CaptureBudgetOptions = {}): CaptureBudgetDecision {
  const settings = { ...DEFAULTS, ...options };
  const captures = events
    .filter((event) => event.type === 'observation.captured')
    .map((event) => ({ event, atMs: Date.parse(event.at), latencyMs: captureLatencyMs(event) }))
    .filter((capture) => Number.isFinite(capture.atMs));

  const capturesLastMinute = captures.filter((capture) => now - capture.atMs <= 60_000).length;
  const capturesLastFiveMinutes = captures.filter((capture) => now - capture.atMs <= 5 * 60_000).length;
  const recentLatencies = captures
    .filter((capture) => now - capture.atMs <= 5 * 60_000 && typeof capture.latencyMs === 'number')
    .map((capture) => capture.latencyMs as number);
  const averageCaptureLatencyMs = average(recentLatencies);

  const reasons: string[] = [];
  let status: CaptureBudgetStatus = 'healthy';

  if (capturesLastMinute >= settings.cooldownPerMinute || capturesLastFiveMinutes >= settings.cooldownPerFiveMinutes) {
    status = 'cooldown';
    reasons.push('capture rate exceeded cooldown budget');
  } else if (capturesLastMinute >= settings.constrainedPerMinute || capturesLastFiveMinutes >= settings.constrainedPerFiveMinutes) {
    status = 'constrained';
    reasons.push('capture rate is constrained');
  } else if (capturesLastMinute >= settings.warmPerMinute) {
    status = 'warm';
    reasons.push('capture rate is warm');
  }

  if (averageCaptureLatencyMs !== undefined) {
    if (averageCaptureLatencyMs >= settings.constrainedLatencyMs && status !== 'cooldown') {
      status = maxStatus(status, 'constrained');
      reasons.push('recent capture latency is constrained');
    } else if (averageCaptureLatencyMs >= settings.warmLatencyMs && status === 'healthy') {
      status = 'warm';
      reasons.push('recent capture latency is warm');
    }
  }

  return {
    status,
    capturesLastMinute,
    capturesLastFiveMinutes,
    averageCaptureLatencyMs,
    cooldownUntil: status === 'cooldown' ? new Date(now + settings.cooldownMs).toISOString() : undefined,
    reason: reasons.length ? reasons.join('; ') : 'capture budget is healthy'
  };
}

function captureLatencyMs(event: AuditEvent): number | undefined {
  const data = typeof event.data === 'object' && event.data !== null ? (event.data as Record<string, unknown>) : undefined;
  const observation = data?.observation;
  if (typeof observation !== 'object' || observation === null) return undefined;
  const telemetry = (observation as Record<string, unknown>).telemetry;
  if (typeof telemetry !== 'object' || telemetry === null) return undefined;
  const latencyMs = (telemetry as Record<string, unknown>).latencyMs;
  if (typeof latencyMs !== 'object' || latencyMs === null) return undefined;
  const total = (latencyMs as Record<string, unknown>).total;
  return typeof total === 'number' && Number.isFinite(total) ? total : undefined;
}

function average(values: number[]): number | undefined {
  if (values.length === 0) return undefined;
  return Math.round(values.reduce((sum, value) => sum + value, 0) / values.length);
}

function maxStatus(a: CaptureBudgetStatus, b: CaptureBudgetStatus): CaptureBudgetStatus {
  const order: CaptureBudgetStatus[] = ['healthy', 'warm', 'constrained', 'cooldown'];
  return order[Math.max(order.indexOf(a), order.indexOf(b))] ?? a;
}
