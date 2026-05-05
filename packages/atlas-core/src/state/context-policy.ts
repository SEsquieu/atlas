import type { ContextStatus } from '../types.js';

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

export type RefreshPolicy = {
  maxAgeMs: number;
  minConfidence: number;
};

export const DEFAULT_REFRESH_POLICY: RefreshPolicy = {
  maxAgeMs: 15_000,
  minConfidence: 0.65
};

export function promptLikelyNeedsVisualContext(text: string): boolean {
  return VISUAL_INTENT_PATTERNS.some((pattern) => pattern.test(text));
}

export function shouldRefreshContext(
  status: ContextStatus | undefined,
  policy: RefreshPolicy = DEFAULT_REFRESH_POLICY
): boolean {
  if (!status?.available) return true;
  if (typeof status.ageMs === 'number' && status.ageMs > policy.maxAgeMs) return true;
  if (typeof status.confidence === 'number' && status.confidence < policy.minConfidence) return true;
  if (status.stability && status.stability !== 'stable') return true;
  if (status.relevant === false) return true;
  return false;
}
