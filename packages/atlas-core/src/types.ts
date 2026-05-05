export type SessionStatus = 'idle' | 'active' | 'paused' | 'done' | 'error';
export type SessionMode = 'manual' | 'assisted' | 'ambient';
export type ContextStability = 'stable' | 'transitioning' | 'unknown';
export type MotionState = 'stationary' | 'handheld-stable' | 'turning' | 'walking' | 'vehicle' | 'unknown';

export type DeviceCapability =
  | 'camera.capture'
  | 'camera.stream'
  | 'audio.listen'
  | 'audio.speak'
  | 'location.current'
  | 'screen.prompt'
  | 'haptics.vibrate';

export type ObservationType = 'image' | 'audio' | 'location' | 'ocr' | 'sensor' | 'composite';

export type ObservationAnalysisKind =
  | 'visual-summary'
  | 'ocr'
  | 'object-detection'
  | 'scene-change'
  | 'quality'
  | 'location-summary'
  | 'audio-transcript'
  | 'custom';

export type ObservationAnalysis = {
  id: string;
  observationId: string;
  kind: ObservationAnalysisKind;
  producedBy: string;
  createdAt: string;
  confidence?: number;
  summary?: string;
  data?: unknown;
  tags?: string[];
};

export type Observation = {
  id: string;
  type: ObservationType;
  capturedAt: string;
  deviceId: string;
  mediaRef?: string;
  data?: unknown;
  quality?: {
    confidence?: number;
    blurScore?: number;
    occluded?: boolean;
    lowLight?: boolean;
    motion?: boolean;
  };
  summary?: string;
  tags?: string[];
  analyses?: ObservationAnalysis[];
};

export type ContextStatus = {
  available: boolean;
  ageMs?: number;
  confidence?: number;
  stability?: ContextStability;
  motionState?: MotionState;
  relevant?: boolean;
  note?: string;
};

export type PerceptionState = {
  latestObservationAt?: string;
  latestImageId?: string;
  latestLocationAt?: string;
  summary?: string;
  confidence: number;
  freshnessMs: number;
  stability: ContextStability;
  motionState?: MotionState;
  sceneDeltaFromPrevious?: number;
  blurScore?: number;
  motionDetected?: boolean;
  relevance?: Record<string, number>;
  notes?: string[];
};

export type ProviderBinding = {
  id: string;
  adapter: string;
  config?: Record<string, unknown>;
};

export type DeviceBinding = {
  id: string;
  adapter: string;
  name?: string;
  capabilities: DeviceCapability[];
  config?: Record<string, unknown>;
};

export type SessionPermissions = {
  observe: boolean;
  captureImage: 'never' | 'on_user_request' | 'during_active_session';
  location: 'never' | 'on_user_request' | 'during_active_session';
  microphone: 'never' | 'wake_only' | 'during_active_session';
  speak: 'never' | 'respond_only' | 'proactive_allowed';
  display: boolean;
  haptics: boolean;
  externalActions: 'never' | 'confirm_each' | 'allowed';
};

export type AtlasSessionState = {
  sessionId: string;
  name?: string;
  status: SessionStatus;
  createdAt: string;
  updatedAt: string;
  eventCursor?: {
    lastEventId?: string;
    lastEventAt?: string;
  };
  goal?: string;
  mode: SessionMode;
  provider: ProviderBinding;
  devices: DeviceBinding[];
  perception: PerceptionState;
  recentObservations: Observation[];
  memory: {
    working: string[];
    durable: string[];
    environmentNotes: string[];
    taskProgress: string[];
  };
  permissions: SessionPermissions;
};

export type AtlasSessionSnapshot = Readonly<AtlasSessionState>;

export type AtlasToolSchema = {
  name: string;
  description: string;
  parameters?: Record<string, unknown>;
};

export type AtlasToolCall = {
  id: string;
  name: string;
  arguments?: Record<string, unknown>;
  reason?: string;
};

export type StateUpdate = {
  path: string;
  value: unknown;
  reason?: string;
};

export type NormalizedSessionTurn = {
  turnId: string;
  session: AtlasSessionSnapshot;
  trigger:
    | { type: 'user'; text: string; mode: 'text' | 'voice' }
    | { type: 'heartbeat'; reason: string }
    | { type: 'tool_result'; toolCallId: string };
  contextStatus: {
    visual?: ContextStatus;
    location?: ContextStatus;
    audio?: ContextStatus;
  };
  observations: Observation[];
  availableTools: AtlasToolSchema[];
  instructions: string[];
};

export type NormalizedAgentResult = {
  turnId: string;
  responseText?: string;
  toolCalls?: AtlasToolCall[];
  memoryUpdates?: StateUpdate[];
  sessionUpdates?: StateUpdate[];
  nextLoopHint?: {
    waitMs?: number;
    requestObservation?: boolean;
    reason?: string;
  };
  done?: boolean;
};

export interface AgentProviderAdapter {
  id: string;
  name: string;
  startSession?(session: AtlasSessionState): Promise<void>;
  endSession?(session: AtlasSessionState): Promise<void>;
  step(turn: NormalizedSessionTurn): Promise<NormalizedAgentResult>;
}

export type CaptureImageOptions = {
  reason?: string;
  quality?: 'low' | 'medium' | 'high';
};

export type LocationOptions = {
  reason?: string;
};

export type SpeakOptions = {
  voice?: string;
  interrupt?: boolean;
};

export type AnalyzeObservationOptions = {
  reason?: string;
  kinds?: ObservationAnalysisKind[];
};

export interface DeviceAdapter {
  id: string;
  name: string;
  capabilities(): Promise<DeviceCapability[]>;
  captureImage?(options?: CaptureImageOptions): Promise<Observation>;
  getLocation?(options?: LocationOptions): Promise<Observation>;
  speak?(text: string, options?: SpeakOptions): Promise<void>;
}

export interface PerceptionAnalyzerAdapter {
  id: string;
  name: string;
  analyze(observation: Observation, options?: AnalyzeObservationOptions): Promise<ObservationAnalysis[]>;
}
