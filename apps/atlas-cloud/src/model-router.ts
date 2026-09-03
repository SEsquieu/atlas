import { z } from "zod";

export const capabilitySchema = z.enum(["fast", "vision", "reasoning", "fallback"]);
export const inferenceRiskSchema = z.enum(["normal", "elevated", "safety_critical"]);
export const latencyClassSchema = z.enum(["latency_critical", "interactive", "background"]);
export const mediaPurposeSchema = z.enum(["heartbeat", "standard_vision", "detail_vision"]);
const reasoningEffortSchema = z.enum(["none", "low", "medium", "high", "xhigh", "max"]);

export type Capability = z.infer<typeof capabilitySchema>;
export type InferenceRisk = z.infer<typeof inferenceRiskSchema>;
export type LatencyClass = z.infer<typeof latencyClassSchema>;
export type MediaPurpose = z.infer<typeof mediaPurposeSchema>;
export type ReasoningEffort = z.infer<typeof reasoningEffortSchema>;
export const MODEL_ROUTER_POLICY_VERSION = "1";

export class NoEligibleModelError extends Error {}

const qualitySchema = z.object({
  fast: z.number().min(0).max(1),
  vision: z.number().min(0).max(1),
  reasoning: z.number().min(0).max(1),
  fallback: z.number().min(0).max(1),
});

export const modelCatalogSchema = z.array(z.object({
  id: z.string().min(1),
  enabled: z.boolean().default(true),
  supportsVision: z.boolean(),
  reasoningEfforts: z.array(reasoningEffortSchema).min(1),
  quality: qualitySchema,
  speed: z.number().min(0).max(1),
  economy: z.number().min(0).max(1),
  inputCreditsPerMillion: z.number().nonnegative(),
  outputCreditsPerMillion: z.number().nonnegative(),
})).min(1).superRefine((models, context) => {
  const seen = new Set<string>();
  for (const model of models) {
    if (seen.has(model.id)) context.addIssue({ code: "custom", message: `Duplicate model id: ${model.id}` });
    seen.add(model.id);
  }
});

export type ModelCatalogEntry = z.infer<typeof modelCatalogSchema>[number];

export interface RoutingIntent {
  capability: Capability;
  risk: InferenceRisk;
  latencyClass: LatencyClass;
  mediaPurpose: MediaPurpose;
  hasImage: boolean;
}

export interface ModelSelection {
  model: ModelCatalogEntry;
  reasoningEffort: ReasoningEffort;
  maxOutputTokens: number;
  imageDetail: "low" | "high";
  score: number;
  eligibleCount: number;
  profile: string;
  reason: string;
}

interface Weights { quality: number; speed: number; economy: number }

function reasoningEffort(intent: RoutingIntent): ReasoningEffort {
  if (intent.risk === "safety_critical") return intent.capability === "reasoning" ? "high" : "medium";
  if (intent.risk === "elevated") return intent.capability === "fast" ? "low" : "medium";
  if (intent.capability === "fast") return "none";
  if (intent.capability === "reasoning") return "medium";
  return "low";
}

function weights(intent: RoutingIntent): Weights {
  if (intent.risk === "safety_critical") return { quality: 0.70, speed: 0.20, economy: 0.10 };
  if (intent.latencyClass === "latency_critical") return { quality: 0.25, speed: 0.60, economy: 0.15 };
  if (intent.latencyClass === "background") return { quality: 0.30, speed: 0.20, economy: 0.50 };
  if (intent.capability === "reasoning") return { quality: 0.60, speed: 0.25, economy: 0.15 };
  if (intent.capability === "vision") return { quality: 0.50, speed: 0.30, economy: 0.20 };
  return { quality: 0.35, speed: 0.40, economy: 0.25 };
}

function maxOutputTokens(intent: RoutingIntent) {
  if (intent.capability === "fast") return 160;
  if (intent.capability === "vision") return intent.risk === "safety_critical" ? 1_000 : 600;
  if (intent.capability === "reasoning") return intent.risk === "safety_critical" ? 5_000 : 2_000;
  return 800;
}

function qualityFloor(risk: InferenceRisk) {
  if (risk === "safety_critical") return 0.80;
  if (risk === "elevated") return 0.65;
  return 0;
}

export function parseModelCatalog(raw: string) {
  return modelCatalogSchema.parse(JSON.parse(raw));
}

export function selectModel(intent: RoutingIntent, catalog: ModelCatalogEntry[]): ModelSelection {
  const effort = reasoningEffort(intent);
  const minimumQuality = qualityFloor(intent.risk);
  const eligible = catalog.filter((model) => model.enabled)
    .filter((model) => !intent.hasImage || model.supportsVision)
    .filter((model) => model.reasoningEfforts.includes(effort))
    .filter((model) => model.quality[intent.capability] >= minimumQuality);
  if (eligible.length === 0) {
    throw new NoEligibleModelError(`No enabled model satisfies image=${intent.hasImage}, reasoning=${effort}, and quality>=${minimumQuality}`);
  }

  const routeWeights = weights(intent);
  const ranked = eligible.map((model) => ({
    model,
    score: model.quality[intent.capability] * routeWeights.quality
      + model.speed * routeWeights.speed
      + model.economy * routeWeights.economy,
  })).sort((left, right) => right.score - left.score || left.model.id.localeCompare(right.model.id));
  const winner = ranked[0];
  const profile = `${intent.capability}:${intent.risk}:${intent.latencyClass}`;
  const reason = [
    `profile=${profile}`,
    `score=${winner.score.toFixed(4)}`,
    `eligible=${eligible.length}`,
    `qualityFloor=${minimumQuality}`,
    `weights=q${routeWeights.quality}/s${routeWeights.speed}/e${routeWeights.economy}`,
    `effort=${effort}`,
  ].join(";");
  return {
    model: winner.model,
    reasoningEffort: effort,
    maxOutputTokens: maxOutputTokens(intent),
    imageDetail: intent.mediaPurpose === "detail_vision" ? "high" : "low",
    score: winner.score,
    eligibleCount: eligible.length,
    profile,
    reason,
  };
}
