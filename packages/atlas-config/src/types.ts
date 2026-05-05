import type { DeviceBinding, ProviderBinding, SessionPermissions } from '@atlas/core';

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
    intervalMs?: number;
  };
  permissions?: Partial<SessionPermissions>;
};
