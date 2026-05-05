import type { AtlasSessionState, ContextStatus, NormalizedSessionTurn, Observation } from '../types.js';
import { evaluateVisualContextFreshness, promptLikelyNeedsVisualContext, type VisualFreshnessAssessment } from '../state/context-policy.js';

export type UserTurnPlan = {
  needsVisualContext: boolean;
  shouldRefreshVisualContext: boolean;
  reason: string;
  visualFreshness?: VisualFreshnessAssessment;
};

export function planUserTurn(text: string, visual: ContextStatus | undefined): UserTurnPlan {
  const needsVisualContext = promptLikelyNeedsVisualContext(text);
  const visualFreshness = needsVisualContext ? evaluateVisualContextFreshness(visual, { text }) : undefined;
  const shouldRefreshVisualContext = visualFreshness?.decision === 'refresh';

  return {
    needsVisualContext,
    shouldRefreshVisualContext,
    reason: shouldRefreshVisualContext
      ? `user request needs fresh physical context: ${visualFreshness?.reasons.join('; ')}`
      : needsVisualContext
        ? visualFreshness?.decision === 'background-refresh'
          ? 'current visual context is usable, but should be refreshed in the background soon'
          : 'current visual context appears usable'
        : 'user request does not obviously require visual context',
    visualFreshness
  };
}

export function buildUserSessionTurn(input: {
  turnId: string;
  session: AtlasSessionState;
  text: string;
  visual?: ContextStatus;
  observations?: Observation[];
}): NormalizedSessionTurn {
  return {
    turnId: input.turnId,
    session: input.session,
    trigger: { type: 'user', text: input.text, mode: 'text' },
    contextStatus: { visual: input.visual },
    observations: input.observations ?? [],
    availableTools: [],
    instructions: [
      'If a user request depends on current physical context and the provided context is stale, unstable, or insufficient, request fresh observation before answering.'
    ]
  };
}
