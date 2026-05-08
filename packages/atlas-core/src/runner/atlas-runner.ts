import type {
  AgentProviderAdapter,
  AtlasSessionState,
  ContextStatus,
  DeviceAdapter,
  NormalizedSessionTurn,
  NormalizedAgentResult,
  Observation,
  PerceptionAnalyzerAdapter
} from '../types.js';
import { assessCaptureBudget } from '../loops/capture-budget.js';
import { planHeartbeatTick, type HeartbeatDecision, type HeartbeatPolicyOptions } from '../loops/heartbeat.js';
import { buildUserSessionTurn, planUserTurn } from '../loops/user-loop.js';
import { analyzeObservationWithPipeline } from '../perception/analysis.js';
import { assessObservationSignificance, type SceneSignificanceDecision } from '../perception/significance.js';
import { CORE_PHYSICAL_TOOLS } from '../tools/registry.js';
import { materializeSessionCheckpoint } from '../store/materialize.js';
import type { SessionStore } from '../store/types.js';

const DEFAULT_HEARTBEAT_PROVIDER_REVIEW_COOLDOWN_MS = 5 * 60_000;
const DUPLICATE_SCENE_MIN_TOKEN_OVERLAP = 3;
const DUPLICATE_SCENE_MIN_SIMILARITY = 0.25;

export type AtlasRunnerOptions = {
  store: SessionStore;
  provider: AgentProviderAdapter;
  devices: DeviceAdapter[];
  analyzers?: PerceptionAnalyzerAdapter[];
  heartbeatPolicy?: Omit<HeartbeatPolicyOptions, 'captureBudget'>;
};

export type RunUserTurnInput = {
  sessionId: string;
  text: string;
  turnId?: string;
  mode?: 'text' | 'voice';
};

export type RunTranscriptTurnInput = {
  sessionId: string;
  transcript: string;
  turnId?: string;
  source?: string;
  confidence?: number;
  language?: string;
};

export type RunUserTurnResult = {
  session: AtlasSessionState;
  plan: ReturnType<typeof planUserTurn>;
  refreshedObservation?: Observation;
  refreshError?: string;
  reusedLastObservationAfterRefreshFailure?: boolean;
  providerResult: NormalizedAgentResult;
};

export type RunHeartbeatTickInput = {
  sessionId: string;
  now?: number;
  heartbeatPolicy?: Omit<HeartbeatPolicyOptions, 'captureBudget'>;
};

export type RunHeartbeatTickResult = {
  session: AtlasSessionState;
  decision: HeartbeatDecision;
  observation?: Observation;
  significance?: SceneSignificanceDecision;
  providerResult?: NormalizedAgentResult;
  providerReviewSkipped?: HeartbeatProviderReviewSkip;
  proactiveSpeechSuppressed?: boolean;
};

export type HeartbeatProviderReviewSkip = {
  reason: string;
  matchedReview?: {
    eventId: string;
    eventAt: string;
    ageMs: number;
    similarity: number;
    tokenOverlap: number;
  };
};

export class AtlasRunner {
  private readonly store: SessionStore;
  private readonly provider: AgentProviderAdapter;
  private readonly devices: DeviceAdapter[];
  private readonly analyzers: PerceptionAnalyzerAdapter[];
  private readonly heartbeatPolicy: Omit<HeartbeatPolicyOptions, 'captureBudget'> | undefined;

  constructor(options: AtlasRunnerOptions) {
    this.store = options.store;
    this.provider = options.provider;
    this.devices = options.devices;
    this.analyzers = options.analyzers ?? [];
    this.heartbeatPolicy = options.heartbeatPolicy;
  }

  async runHeartbeatTick(input: RunHeartbeatTickInput): Promise<RunHeartbeatTickResult> {
    const record = await this.store.load(input.sessionId);
    if (!record) throw new Error(`Session not found: ${input.sessionId}`);

    let session = materializeSessionCheckpoint(record.state, record.events);
    const now = input.now ?? Date.now();
    const captureBudget = assessCaptureBudget(record.events, now);
    const decision = planHeartbeatTick(session, now, {
      ...mergeHeartbeatPolicy(this.heartbeatPolicy, input.heartbeatPolicy),
      captureBudget
    });

    const heartbeatEvent = await this.store.appendEvent(session.sessionId, {
      type: 'heartbeat.tick',
      data: { decision }
    });
    session = materializeSessionCheckpoint(session, [heartbeatEvent]);

    let observation: Observation | undefined;
    let significance: SceneSignificanceDecision | undefined;
    let providerResult: NormalizedAgentResult | undefined;
    let providerReviewSkipped: HeartbeatProviderReviewSkip | undefined;
    let proactiveSpeechSuppressed = false;
    if (decision.shouldCapture) {
      const previousObservation = session.recentObservations.at(-1);
      observation = await this.captureCurrentView(session, decision.reason);
      const captureEvent = await this.store.appendEvent(session.sessionId, {
        type: 'observation.captured',
        data: { observation, reason: decision.reason, source: 'heartbeat' }
      });
      session = materializeSessionCheckpoint(session, [captureEvent]);

      significance = assessObservationSignificance({ previous: previousObservation, current: observation });
      const significanceEvent = await this.store.appendEvent(session.sessionId, {
        type: 'perception.significance',
        data: {
          observationId: observation.id,
          previousObservationId: previousObservation?.id,
          decision: significance,
          source: 'heartbeat'
        }
      });
      session = materializeSessionCheckpoint(session, [significanceEvent]);

      if (significance.shouldCallProvider) {
        const reviewPlan = planHeartbeatProviderReview({
          events: record.events,
          observation,
          significance,
          now,
          policy: mergeHeartbeatPolicy(this.heartbeatPolicy, input.heartbeatPolicy)
        });
        if (reviewPlan.shouldReview) {
          const review = await this.runHeartbeatProviderReview(session, significance, reviewPlan.scene);
          providerResult = review.providerResult;
          proactiveSpeechSuppressed = review.proactiveSpeechSuppressed;
          session = review.session;
        } else {
          providerReviewSkipped = {
            reason: reviewPlan.reason,
            matchedReview: reviewPlan.matchedReview
          };
          const skippedEvent = await this.store.appendEvent(session.sessionId, {
            type: 'provider.review_skipped',
            data: {
              provider: this.provider.id,
              source: 'heartbeat',
              significance,
              review: { scene: reviewPlan.scene },
              reason: reviewPlan.reason,
              matchedReview: reviewPlan.matchedReview
            }
          });
          session = materializeSessionCheckpoint(session, [skippedEvent]);
        }
      }

      await this.store.saveState(session);
    } else {
      await this.store.saveState(session);
    }

    return { session, decision, observation, significance, providerResult, providerReviewSkipped, proactiveSpeechSuppressed };
  }

  async runTranscriptTurn(input: RunTranscriptTurnInput): Promise<RunUserTurnResult> {
    const text = input.transcript.trim();
    if (!text) throw new Error('Transcript text is required.');

    const record = await this.store.load(input.sessionId);
    if (!record) throw new Error(`Session not found: ${input.sessionId}`);
    let session = materializeSessionCheckpoint(record.state, record.events);

    const transcriptEvent = await this.store.appendEvent(session.sessionId, {
      type: 'audio.transcript_received',
      data: {
        text,
        source: input.source ?? 'stt',
        confidence: input.confidence,
        language: input.language,
        turnId: input.turnId
      }
    });
    session = materializeSessionCheckpoint(session, [transcriptEvent]);
    await this.store.saveState(session);

    return await this.runUserTurn({
      sessionId: input.sessionId,
      text,
      turnId: input.turnId,
      mode: 'voice'
    });
  }

  async runUserTurn(input: RunUserTurnInput): Promise<RunUserTurnResult> {
    const record = await this.store.load(input.sessionId);
    if (!record) throw new Error(`Session not found: ${input.sessionId}`);

    let session = materializeSessionCheckpoint(record.state, record.events);
    const visualStatus = contextStatusFromSession(session);
    const plan = planUserTurn(input.text, visualStatus);

    const utteranceEvent = await this.store.appendEvent(session.sessionId, {
      type: 'user.utterance',
      data: { text: input.text, mode: input.mode ?? 'text', plan }
    });
    session = materializeSessionCheckpoint(session, [utteranceEvent]);

    let refreshedObservation: Observation | undefined;
    let refreshError: string | undefined;
    let reusedLastObservationAfterRefreshFailure = false;
    if (plan.shouldRefreshVisualContext) {
      try {
        refreshedObservation = await this.captureCurrentView(session, plan.reason);
        const captureEvent = await this.store.appendEvent(session.sessionId, {
          type: 'observation.captured',
          data: { observation: refreshedObservation, reason: plan.reason }
        });
        session = materializeSessionCheckpoint(session, [captureEvent]);
        await this.store.saveState(session);
      } catch (error) {
        refreshError = formatError(error);
        const failureEvent = await this.store.appendEvent(session.sessionId, {
          type: 'visual.refresh_failed',
          data: { reason: plan.reason, error: refreshError, fallbackObservationId: session.recentObservations.at(-1)?.id }
        });
        session = materializeSessionCheckpoint(session, [failureEvent]);
        await this.store.saveState(session);

        if (!plan.needsVisualContext || session.recentObservations.length === 0) throw error;
        reusedLastObservationAfterRefreshFailure = true;
      }
    }

    const turn = buildUserSessionTurn({
      turnId: input.turnId ?? crypto.randomUUID(),
      session,
      text: input.text,
      visual: contextStatusFromSession(session),
      observations: session.recentObservations
    });

    if (reusedLastObservationAfterRefreshFailure) {
      turn.instructions.push(
        `Atlas attempted to refresh visual context before answering, but refresh failed: ${refreshError}. ` +
          'Answer from the latest available observation if it is useful, and explicitly mention that it may be stale.'
      );
    }

    turn.availableTools = CORE_PHYSICAL_TOOLS;

    await this.store.appendEvent(session.sessionId, {
      type: 'provider.requested',
      data: { provider: this.provider.id, turnId: turn.turnId }
    });

    const providerResult = await this.provider.step(turn);

    await this.store.appendEvent(session.sessionId, {
      type: 'provider.responded',
      data: { provider: this.provider.id, turnId: turn.turnId, result: providerResult }
    });

    if (providerResult.responseText) {
      session = await this.emitAgentSpeech(session, providerResult.responseText, { source: 'user-turn' });
    }

    const latestRecord = await this.store.load(session.sessionId);
    if (!latestRecord) throw new Error(`Session disappeared while running turn: ${session.sessionId}`);
    const latestSession = materializeSessionCheckpoint(latestRecord.state, latestRecord.events);
    await this.store.saveState(latestSession);

    return {
      session: latestSession,
      plan,
      refreshedObservation,
      refreshError,
      reusedLastObservationAfterRefreshFailure,
      providerResult
    };
  }

  private async captureCurrentView(session: AtlasSessionState, reason: string): Promise<Observation> {
    if (session.permissions.captureImage === 'never') {
      throw new Error('Image capture is not permitted for this session.');
    }

    const device = this.devices.find((candidate) => typeof candidate.captureImage === 'function');
    if (!device?.captureImage) {
      throw new Error('No bound device can capture images.');
    }

    await this.store.appendEvent(session.sessionId, {
      type: 'tool.requested',
      data: { toolName: 'capture_current_view', reason, deviceId: device.id }
    });

    const rawObservation = await device.captureImage({ reason, quality: 'medium' });
    const observation = await analyzeObservationWithPipeline(rawObservation, {
      analyzers: this.analyzers,
      reason,
      kinds: ['visual-summary', 'quality']
    });

    await this.store.appendEvent(session.sessionId, {
      type: 'tool.completed',
      data: {
        toolName: 'capture_current_view',
        resultRef: observation.mediaRef,
        observationId: observation.id,
        analysisCount: observation.analyses?.length ?? 0
      }
    });

    return observation;
  }

  private async runHeartbeatProviderReview(
    session: AtlasSessionState,
    significance: SceneSignificanceDecision,
    scene: HeartbeatSceneFingerprint
  ): Promise<{ session: AtlasSessionState; providerResult: NormalizedAgentResult; proactiveSpeechSuppressed: boolean }> {
    const turnId = crypto.randomUUID();
    const turn = buildHeartbeatSessionTurn({ turnId, session, significance });

    await this.store.appendEvent(session.sessionId, {
      type: 'provider.requested',
      data: {
        provider: this.provider.id,
        turnId,
        source: 'heartbeat',
        significance,
        review: { scene }
      }
    });

    const providerResult = await this.provider.step(turn);

    await this.store.appendEvent(session.sessionId, {
      type: 'provider.responded',
      data: { provider: this.provider.id, turnId, source: 'heartbeat', result: providerResult, review: { scene } }
    });

    const maySpeakProactively = significance.shouldNotifyUser && session.permissions.speak === 'proactive_allowed';
    const proactiveSpeechSuppressed = Boolean(providerResult.responseText && !maySpeakProactively);
    if (providerResult.responseText && maySpeakProactively) {
      session = await this.emitAgentSpeech(session, providerResult.responseText, { source: 'heartbeat', significance });
    } else if (proactiveSpeechSuppressed) {
      await this.store.appendEvent(session.sessionId, {
        type: 'agent.speech_suppressed',
        data: {
          text: providerResult.responseText,
          source: 'heartbeat',
          reason: significance.shouldNotifyUser
            ? `proactive speech permission is ${session.permissions.speak}`
            : 'scene significance requests provider review but not user notification',
          significance
        }
      });
    }

    const latestRecord = await this.store.load(session.sessionId);
    if (!latestRecord) throw new Error(`Session disappeared while running heartbeat provider review: ${session.sessionId}`);
    return {
      session: materializeSessionCheckpoint(latestRecord.state, latestRecord.events),
      providerResult,
      proactiveSpeechSuppressed
    };
  }

  private async emitAgentSpeech(
    session: AtlasSessionState,
    text: string,
    input: { source: 'user-turn' | 'heartbeat'; significance?: SceneSignificanceDecision }
  ): Promise<AtlasSessionState> {
    const speechEvent = await this.store.appendEvent(session.sessionId, {
      type: 'agent.speech',
      data: { text, source: input.source, significance: input.significance }
    });
    let nextSession = materializeSessionCheckpoint(session, [speechEvent]);

    const speaker = this.devices.find((candidate) => typeof candidate.speak === 'function');
    if (!speaker?.speak || session.permissions.speak === 'never') return nextSession;

    const requestedEvent = await this.store.appendEvent(session.sessionId, {
      type: 'audio.speech_requested',
      data: { deviceId: speaker.id, source: input.source, text }
    });
    nextSession = materializeSessionCheckpoint(nextSession, [requestedEvent]);

    try {
      await speaker.speak(text, { interrupt: input.source === 'heartbeat' });
      const completedEvent = await this.store.appendEvent(session.sessionId, {
        type: 'audio.speech_completed',
        data: { deviceId: speaker.id, source: input.source }
      });
      nextSession = materializeSessionCheckpoint(nextSession, [completedEvent]);
    } catch (error) {
      const failedEvent = await this.store.appendEvent(session.sessionId, {
        type: 'audio.speech_failed',
        data: { deviceId: speaker.id, source: input.source, error: formatError(error), textFallbackPreserved: true }
      });
      nextSession = materializeSessionCheckpoint(nextSession, [failedEvent]);
    }

    return nextSession;
  }
}

function buildHeartbeatSessionTurn(input: {
  turnId: string;
  session: AtlasSessionState;
  significance: SceneSignificanceDecision;
}): NormalizedSessionTurn {
  return {
    turnId: input.turnId,
    session: input.session,
    trigger: { type: 'heartbeat', reason: input.significance.reason },
    contextStatus: { visual: contextStatusFromSession(input.session) },
    observations: input.session.recentObservations,
    availableTools: [],
    instructions: [
      'Atlas heartbeat detected a physical scene change during ambient perception.',
      `Significance level: ${input.significance.level}; score: ${input.significance.score.toFixed(2)}; reason: ${input.significance.reason}.`,
      'Review the latest observation and decide whether there is anything useful to know. Do not assume the user asked a question.',
      input.significance.shouldNotifyUser
        ? 'If the scene appears actionable or safety-relevant, provide a concise notification-worthy response.'
        : 'Return concise internal review text if useful, but this response is not automatically spoken to the user.'
    ]
  };
}

type HeartbeatSceneFingerprint = {
  observationId?: string;
  summary?: string;
  tokens: string[];
};

type HeartbeatProviderReviewPlan =
  | {
      shouldReview: true;
      scene: HeartbeatSceneFingerprint;
    }
  | {
      shouldReview: false;
      scene: HeartbeatSceneFingerprint;
      reason: string;
      matchedReview?: {
        eventId: string;
        eventAt: string;
        ageMs: number;
        similarity: number;
        tokenOverlap: number;
      };
    };

function planHeartbeatProviderReview(input: {
  events: { id: string; type: string; at: string; data?: unknown }[];
  observation: Observation;
  significance: SceneSignificanceDecision;
  now: number;
  policy: Omit<HeartbeatPolicyOptions, 'captureBudget'>;
}): HeartbeatProviderReviewPlan {
  const scene = heartbeatSceneFingerprint(input.observation);
  if (input.significance.shouldNotifyUser) return { shouldReview: true, scene };

  const cooldownMs = finiteOrUndefined(input.policy.providerReviewCooldownMs) ?? DEFAULT_HEARTBEAT_PROVIDER_REVIEW_COOLDOWN_MS;
  if (cooldownMs <= 0 || scene.tokens.length < DUPLICATE_SCENE_MIN_TOKEN_OVERLAP) return { shouldReview: true, scene };

  for (const event of [...input.events].reverse()) {
    if (event.type !== 'provider.responded') continue;
    const data = dataObject(event.data);
    if (data?.source !== 'heartbeat') continue;
    const previousScene = sceneFromReview(data.review);
    if (!previousScene || previousScene.tokens.length < DUPLICATE_SCENE_MIN_TOKEN_OVERLAP) continue;

    const eventAtMs = Date.parse(event.at);
    if (!Number.isFinite(eventAtMs)) continue;
    const ageMs = Math.max(0, input.now - eventAtMs);
    if (ageMs > cooldownMs) continue;

    const similarity = tokenSimilarity(scene.tokens, previousScene.tokens);
    const tokenOverlap = tokenIntersectionCount(scene.tokens, previousScene.tokens);
    if (tokenOverlap >= DUPLICATE_SCENE_MIN_TOKEN_OVERLAP && similarity >= DUPLICATE_SCENE_MIN_SIMILARITY) {
      return {
        shouldReview: false,
        scene,
        reason: `meaningful scene already received heartbeat provider review ${formatMs(ageMs)} ago`,
        matchedReview: {
          eventId: event.id,
          eventAt: event.at,
          ageMs,
          similarity,
          tokenOverlap
        }
      };
    }
  }

  return { shouldReview: true, scene };
}

function heartbeatSceneFingerprint(observation: Observation): HeartbeatSceneFingerprint {
  const summary = observation.summary ?? observation.analyses?.find((analysis) => analysis.summary?.trim())?.summary;
  return {
    observationId: observation.id,
    summary,
    tokens: [...tokenizeScene(summary)].sort()
  };
}

function sceneFromReview(value: unknown): HeartbeatSceneFingerprint | undefined {
  const review = dataObject(value);
  const scene = dataObject(review?.scene);
  const tokens = Array.isArray(scene?.tokens) ? scene.tokens.filter((token): token is string => typeof token === 'string') : [];
  if (!tokens.length) return undefined;
  return {
    observationId: typeof scene?.observationId === 'string' ? scene.observationId : undefined,
    summary: typeof scene?.summary === 'string' ? scene.summary : undefined,
    tokens
  };
}

function tokenizeScene(text: string | undefined): Set<string> {
  const stopwords = new Set(['a', 'an', 'and', 'are', 'at', 'from', 'in', 'is', 'near', 'of', 'on', 'the', 'to', 'with', 'you', 'your']);
  return new Set(
    (text ?? '')
      .toLowerCase()
      .replace(/[^a-z0-9\s-]/g, ' ')
      .split(/\s+/)
      .map((token) => token.trim())
      .filter((token) => token.length > 2 && !stopwords.has(token))
  );
}

function tokenSimilarity(left: string[], right: string[]): number {
  const leftSet = new Set(left);
  const rightSet = new Set(right);
  if (leftSet.size === 0 && rightSet.size === 0) return 1;
  if (leftSet.size === 0 || rightSet.size === 0) return 0;
  return tokenIntersectionCount(left, right) / new Set([...leftSet, ...rightSet]).size;
}

function tokenIntersectionCount(left: string[], right: string[]): number {
  const rightSet = new Set(right);
  let count = 0;
  for (const token of new Set(left)) {
    if (rightSet.has(token)) count += 1;
  }
  return count;
}

function dataObject(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : undefined;
}

function formatError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function formatMs(value: number): string {
  if (value < 1000) return `${Math.round(value)}ms`;
  if (value < 60_000) return `${Math.round(value / 1000)}s`;
  return `${Math.round(value / 60_000)}m`;
}

function finiteOrUndefined(value: number | undefined): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function mergeHeartbeatPolicy(
  base: Omit<HeartbeatPolicyOptions, 'captureBudget'> | undefined,
  override: Omit<HeartbeatPolicyOptions, 'captureBudget'> | undefined
): Omit<HeartbeatPolicyOptions, 'captureBudget'> {
  return {
    ...base,
    ...override,
    cadence: {
      ...base?.cadence,
      ...override?.cadence
    }
  };
}

export function contextStatusFromSession(session: AtlasSessionState): ContextStatus {
  const latestAt = session.perception.latestObservationAt;
  const ageMs = latestAt ? Math.max(0, Date.now() - Date.parse(latestAt)) : undefined;

  return {
    available: Boolean(session.perception.latestImageId),
    ageMs,
    confidence: session.perception.confidence,
    stability: session.perception.stability,
    motionState: session.perception.motionState,
    relevant: Boolean(session.perception.latestImageId),
    latencyMs: session.perception.observationLatencyMs,
    analysisLatencyMs: session.perception.analysisLatencyMs,
    refreshHealth: session.perception.health?.visualRefresh,
    note: session.perception.notes?.join(' ')
  };
}
