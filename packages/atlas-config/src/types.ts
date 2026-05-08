import type { DeviceBinding, HeartbeatCadenceMode, ProviderBinding, SessionPermissions } from '@atlas/core';

export type AtlasConfig = {
  version?: 1;
  store?: {
    path?: string;
  };
  sessions?: Record<string, AtlasSessionConfig>;
};

export type AtlasSessionConfig = {
  name?: string;
  goal?: string;
  mode?: 'manual' | 'assisted' | 'ambient';
  provider?: ProviderBinding;
  devices?: DeviceBinding[];
  analyzers?: string[];
  heartbeat?: {
    enabled?: boolean;
    /**
     * Legacy scheduler hint for external runners. Runtime cadence policy lives under `policy`.
     */
    intervalMs?: number;
    policy?: AtlasHeartbeatPolicyConfig;
  };
  permissions?: Partial<SessionPermissions>;
};

export type AtlasHeartbeatPolicyConfig = {
  cadence?: Partial<Record<HeartbeatCadenceMode, number>>;
  minDelayMs?: number;
  maxDelayMs?: number;
  baseStaleAfterMs?: number;
  minStaleAfterMs?: number;
  maxStaleAfterMs?: number;
  expectedRefreshLatencyMs?: number;
  minExpectedRefreshLatencyMs?: number;
  maxExpectedRefreshLatencyMs?: number;
  refreshSafetyMarginMs?: number;
  refreshFailureRetryMs?: number;
};
