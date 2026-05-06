import type {
  AgentProviderAdapter,
  AtlasSessionState,
  ContextStatus,
  DeviceAdapter,
  NormalizedAgentResult,
  Observation,
  PerceptionAnalyzerAdapter
} from '../types.js';
import { planHeartbeatTick, type HeartbeatDecision } from '../loops/heartbeat.js';
import { buildUserSessionTurn, planUserTurn } from '../loops/user-loop.js';
import { analyzeObservationWithPipeline } from '../perception/analysis.js';
import { assessObservationSignificance, type SceneSignificanceDecision } from '../perception/significance.js';
import { CORE_PHYSICAL_TOOLS } from '../tools/registry.js';
import { materializeSessionCheckpoint } from '../store/materialize.js';
import type { SessionStore } from '../store/types.js';

export type AtlasRunnerOptions = {
  store: SessionStore;
  provider: AgentProviderAdapter;
  devices: DeviceAdapter[];
  analyzers?: PerceptionAnalyzerAdapter[];
};

export type RunUserTurnInput = {
  sessionId: string;
  text: string;
  turnId?: string;
  mode?: 'text' | 'voice';
};

export type RunUserTurnResult = {
  session: AtlasSessionState;
  plan: ReturnType<typeof planUserTurn>;
  refreshedObservation?: Observation;
  providerResult: NormalizedAgentResult;
};

export type RunHeartbeatTickInput = {
  sessionId: string;
  now?: number;
};

export type RunHeartbeatTickResult = {
  session: AtlasSessionState;
  decision: HeartbeatDecision;
  observation?: Observation;
  significance?: SceneSignificanceDecision;
};

export class AtlasRunner {
  private readonly store: SessionStore;
  private readonly provider: AgentProviderAdapter;
  private readonly devices: DeviceAdapter[];
  private readonly analyzers: PerceptionAnalyzerAdapter[];

  constructor(options: AtlasRunnerOptions) {
    this.store = options.store;
    this.provider = options.provider;
    this.devices = options.devices;
    this.analyzers = options.analyzers ?? [];
  }

  async runHeartbeatTick(input: RunHeartbeatTickInput): Promise<RunHeartbeatTickResult> {
    const record = await this.store.load(input.sessionId);
    if (!record) throw new Error(`Session not found: ${input.sessionId}`);

    let session = materializeSessionCheckpoint(record.state, record.events);
    const decision = planHeartbeatTick(session, input.now ?? Date.now());

    const heartbeatEvent = await this.store.appendEvent(session.sessionId, {
      type: 'heartbeat.tick',
      data: { decision }
    });
    session = materializeSessionCheckpoint(session, [heartbeatEvent]);

    let observation: Observation | undefined;
    let significance: SceneSignificanceDecision | undefined;
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
      await this.store.saveState(session);
    } else {
      await this.store.saveState(session);
    }

    return { session, decision, observation, significance };
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
    if (plan.shouldRefreshVisualContext) {
      refreshedObservation = await this.captureCurrentView(session, plan.reason);
      const captureEvent = await this.store.appendEvent(session.sessionId, {
        type: 'observation.captured',
        data: { observation: refreshedObservation, reason: plan.reason }
      });
      session = materializeSessionCheckpoint(session, [captureEvent]);
      await this.store.saveState(session);
    }

    const turn = buildUserSessionTurn({
      turnId: input.turnId ?? crypto.randomUUID(),
      session,
      text: input.text,
      visual: contextStatusFromSession(session),
      observations: session.recentObservations
    });

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
      await this.store.appendEvent(session.sessionId, {
        type: 'agent.speech',
        data: { text: providerResult.responseText }
      });
    }

    const latestRecord = await this.store.load(session.sessionId);
    if (!latestRecord) throw new Error(`Session disappeared while running turn: ${session.sessionId}`);
    const latestSession = materializeSessionCheckpoint(latestRecord.state, latestRecord.events);
    await this.store.saveState(latestSession);

    return {
      session: latestSession,
      plan,
      refreshedObservation,
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
