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
        ? visualFreshness?.decision === 'degraded-reuse'
          ? 'current visual context is stale, but refresh performance is degraded; reuse last stable context to avoid churn'
          : visualFreshness?.decision === 'background-refresh'
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
    interaction: {
      pendingClarification: input.session.interaction.pendingClarification
    },
    availableTools: [],
    instructions: [
      'Optimize for shared understanding, not maximum response completeness. Speak like a capable coworker: direct, contextual, and natural.',
      'Resolve ambiguity from supplied observations and conversation before asking the user. Make a quiet assumption only when it is low-risk and easy to reverse.',
      'If ambiguity would materially change physical guidance, tool effects, safety, cost, or task direction, return one focused clarification question before deeper reasoning or action.',
      'A tangent does not cancel the active task or pending clarification. Answer it briefly when appropriate, then retain the unresolved question unless the user clearly resolves or abandons it.',
      'Treat short fragments such as “the black one” or “behind that” as possible answers to the pending clarification. Explicitly report whether the clarification was resolved, deferred, or abandoned.',
      'Never propose a tool call while a blocking clarification remains unresolved.',
      'If a user request depends on current physical context and the provided context is stale, unstable, or insufficient, request fresh observation before answering.',
      'If visual refresh health is slow or degraded, avoid repeatedly asking for fresh observations unless the task is high-risk, navigational, or the user explicitly needs current confirmation.'
    ]
  };
}
