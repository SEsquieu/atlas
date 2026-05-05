import type { AgentProviderAdapter, NormalizedAgentResult, NormalizedSessionTurn } from '@atlas/core';

export type OpenClawStep = (turn: NormalizedSessionTurn) => Promise<NormalizedAgentResult>;

export type OpenClawProviderAdapterOptions = {
  id?: string;
  name?: string;
  step: OpenClawStep;
};

export function createOpenClawProviderAdapter(options: OpenClawProviderAdapterOptions): AgentProviderAdapter {
  return {
    id: options.id ?? 'openclaw-default',
    name: options.name ?? 'OpenClaw',
    step: options.step
  };
}
