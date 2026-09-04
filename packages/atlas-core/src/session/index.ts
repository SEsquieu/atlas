import type {
  AtlasSessionState,
  DeviceBinding,
  PrincipalRef,
  ProviderBinding,
  RuntimePolicyRef,
  SessionPermissions,
  SessionPlacement,
  TaskRunState,
  WorkspaceRef
} from '../types.js';

export const UNKNOWN_FRESHNESS_MS = Number.MAX_SAFE_INTEGER;

export type CreateSessionInput = {
  sessionId: string;
  name?: string;
  goal?: string;
  provider: ProviderBinding;
  devices?: DeviceBinding[];
  permissions?: Partial<SessionPermissions>;
  workspace?: WorkspaceRef;
  actor?: PrincipalRef;
  placement?: SessionPlacement;
  taskRun?: TaskRunState;
  policy?: RuntimePolicyRef;
  now?: string;
};

export const LOCAL_PERSONAL_WORKSPACE: WorkspaceRef = {
  workspaceId: 'workspace:personal:local',
  kind: 'personal',
  name: 'Personal'
};

export function createSessionState(input: CreateSessionInput): AtlasSessionState {
  const now = input.now ?? new Date().toISOString();

  return {
    sessionId: input.sessionId,
    workspace: input.workspace ?? LOCAL_PERSONAL_WORKSPACE,
    actor: input.actor,
    placement: input.placement,
    taskRun: input.taskRun,
    policy: input.policy,
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
      scoped: [],
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
