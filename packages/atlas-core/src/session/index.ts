import type { AtlasSessionState, DeviceBinding, ProviderBinding, SessionPermissions } from '../types.js';

export const UNKNOWN_FRESHNESS_MS = Number.MAX_SAFE_INTEGER;

export type CreateSessionInput = {
  sessionId: string;
  name?: string;
  goal?: string;
  provider: ProviderBinding;
  devices?: DeviceBinding[];
  permissions?: Partial<SessionPermissions>;
  now?: string;
};

export function createSessionState(input: CreateSessionInput): AtlasSessionState {
  const now = input.now ?? new Date().toISOString();

  return {
    sessionId: input.sessionId,
    name: input.name,
    status: 'idle',
    createdAt: now,
    updatedAt: now,
    goal: input.goal,
    mode: 'assisted',
    provider: input.provider,
    devices: input.devices ?? [],
    perception: {
      confidence: 0,
      freshnessMs: UNKNOWN_FRESHNESS_MS,
      stability: 'unknown',
      motionState: 'unknown',
      notes: ['No observations captured yet.']
    },
    recentObservations: [],
    memory: {
      working: [],
      durable: [],
      environmentNotes: [],
      taskProgress: []
    },
    permissions: {
      observe: true,
      captureImage: 'during_active_session',
      location: 'on_user_request',
      microphone: 'wake_only',
      speak: 'respond_only',
      display: true,
      haptics: false,
      externalActions: 'never',
      ...input.permissions
    }
  };
}
