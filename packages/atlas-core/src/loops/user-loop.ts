import type { AtlasSessionState, ContextStatus, NormalizedSessionTurn, Observation } from '../types.js';
import { promptLikelyNeedsVisualContext, shouldRefreshContext } from '../state/context-policy.js';

export type UserTurnPlan = {
  needsVisualContext: boolean;
  shouldRefreshVisualContext: boolean;
  reason: string;
};

export function planUserTurn(text: string, visual: ContextStatus | undefined): UserTurnPlan {
  const needsVisualContext = promptLikelyNeedsVisualContext(text);
  const shouldRefreshVisualContext = needsVisualContext && shouldRefreshContext(visual);

  return {
    needsVisualContext,
    shouldRefreshVisualContext,
    reason: shouldRefreshVisualContext
      ? 'user request needs fresh physical context'
      : needsVisualContext
        ? 'current visual context appears usable'
        : 'user request does not obviously require visual context'
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
