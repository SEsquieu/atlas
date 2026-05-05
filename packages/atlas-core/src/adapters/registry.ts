import type {
  AgentProviderAdapter,
  DeviceAdapter,
  DeviceBinding,
  PerceptionAnalyzerAdapter,
  ProviderBinding
} from '../types.js';

export type AtlasAdapterRegistry = {
  resolveProvider(binding: ProviderBinding): AgentProviderAdapter | undefined;
  resolveDevices(bindings: DeviceBinding[]): DeviceAdapter[];
  resolveAnalyzers?(ids?: string[]): PerceptionAnalyzerAdapter[];
};

export type StaticAdapterRegistryOptions = {
  providers?: AgentProviderAdapter[];
  devices?: DeviceAdapter[];
  analyzers?: PerceptionAnalyzerAdapter[];
};

export function createStaticAdapterRegistry(options: StaticAdapterRegistryOptions = {}): AtlasAdapterRegistry {
  const providers = new Map(options.providers?.flatMap((provider) => [[provider.id, provider], [provider.name, provider]]) ?? []);
  const devices = new Map(options.devices?.flatMap((device) => [[device.id, device], [device.name, device]]) ?? []);
  const analyzers = new Map(options.analyzers?.flatMap((analyzer) => [[analyzer.id, analyzer], [analyzer.name, analyzer]]) ?? []);

  return {
    resolveProvider(binding) {
      return providers.get(binding.id) ?? providers.get(binding.adapter);
    },
    resolveDevices(bindings) {
      return bindings
        .map((binding) => devices.get(binding.id) ?? devices.get(binding.adapter))
        .filter((device): device is DeviceAdapter => Boolean(device));
    },
    resolveAnalyzers(ids) {
      if (!ids?.length) return [...analyzers.values()].filter(uniqueById);
      return ids.map((id) => analyzers.get(id)).filter((analyzer): analyzer is PerceptionAnalyzerAdapter => Boolean(analyzer));
    }
  };
}

function uniqueById<T extends { id: string }>(value: T, index: number, values: T[]): boolean {
  return values.findIndex((candidate) => candidate.id === value.id) === index;
}
