import type { ResolvedRuntimePolicy, RuntimePolicy } from '../types.js';

/** Layers are ordered from broadest to narrowest: defaults, organization, site, station, procedure, assignment, run. */
export function resolveRuntimePolicy(layers: RuntimePolicy[]): ResolvedRuntimePolicy {
  if (layers.length === 0) throw new Error('At least one runtime policy layer is required.');
  const last = layers[layers.length - 1]!;
  return {
    policyId: `resolved:${layers.map((layer) => `${layer.policyId}@${layer.revision}`).join('+')}`,
    revision: last.revision,
    inference: mergeInference(layers),
    retention: mergeRetention(layers),
    interaction: mergeInteraction(layers),
    tools: mergeToolPolicy(layers),
    sourcePolicies: layers.map(({ policyId, revision }) => ({ policyId, revision }))
  };
}

function mergeObjects<T extends object>(values: Array<T | undefined>): T | undefined {
  const present = values.filter((value): value is T => Boolean(value));
  return present.length ? Object.assign({}, ...present) : undefined;
}

function mergeInference(layers: RuntimePolicy[]): RuntimePolicy['inference'] {
  const values = layers.map((layer) => layer.inference).filter((value): value is NonNullable<RuntimePolicy['inference']> => Boolean(value));
  if (!values.length) return undefined;
  const merged = mergeObjects(values)!;
  const providerSets = values.filter((value) => value.allowedProviders).map((value) => new Set(value.allowedProviders));
  if (providerSets.length) merged.allowedProviders = [...providerSets.reduce((current, next) => new Set([...current].filter((provider) => next.has(provider))))];
  const budgets = values.map((value) => value.maxCostMicrosPerRun).filter((value): value is number => value !== undefined);
  if (budgets.length) merged.maxCostMicrosPerRun = Math.min(...budgets);
  if (values.some((value) => value.cloudAllowed === false)) merged.cloudAllowed = false;
  if (values.some((value) => value.imagesMayLeaveDevice === false)) merged.imagesMayLeaveDevice = false;
  return merged;
}

function mergeRetention(layers: RuntimePolicy[]): RuntimePolicy['retention'] {
  const values = layers.map((layer) => layer.retention).filter((value): value is NonNullable<RuntimePolicy['retention']> => Boolean(value));
  if (!values.length) return undefined;
  const merged = mergeObjects(values)!;
  const mediaTtls = values.map((value) => value.mediaTtlMs).filter((value): value is number => value !== undefined);
  const eventTtls = values.map((value) => value.eventTtlMs).filter((value): value is number => value !== undefined);
  if (mediaTtls.length) merged.mediaTtlMs = Math.min(...mediaTtls);
  if (eventTtls.length) merged.eventTtlMs = Math.min(...eventTtls);
  if (values.some((value) => value.retainRawMedia === false)) merged.retainRawMedia = false;
  return merged;
}

function mergeInteraction(layers: RuntimePolicy[]): RuntimePolicy['interaction'] {
  const values = layers.map((layer) => layer.interaction).filter((value): value is NonNullable<RuntimePolicy['interaction']> => Boolean(value));
  if (!values.length) return undefined;
  const merged = mergeObjects(values)!;
  if (values.some((value) => value.proactiveSpeechAllowed === false)) merged.proactiveSpeechAllowed = false;
  if (values.some((value) => value.confirmationForExternalActions === true)) merged.confirmationForExternalActions = true;
  return merged;
}

function mergeToolPolicy(layers: RuntimePolicy[]): RuntimePolicy['tools'] {
  const toolLayers = layers.map((layer) => layer.tools).filter((value): value is NonNullable<RuntimePolicy['tools']> => Boolean(value));
  if (!toolLayers.length) return undefined;
  const deny = new Set(toolLayers.flatMap((layer) => layer.deny ?? []));
  const explicitAllows = toolLayers.filter((layer) => layer.allow).map((layer) => new Set(layer.allow));
  const allow = explicitAllows.length
    ? [...explicitAllows.reduce((current, next) => new Set([...current].filter((tool) => next.has(tool))))].filter((tool) => !deny.has(tool))
    : undefined;
  return { allow, deny: [...deny] };
}
