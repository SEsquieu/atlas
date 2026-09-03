import { z } from "zod";
import { env } from "./env";

const ratesSchema = z.record(z.string(), z.object({ inputCreditsPerMillion: z.number().nonnegative(), outputCreditsPerMillion: z.number().nonnegative() }));
const routesSchema = z.object({ fast: z.string(), vision: z.string(), reasoning: z.string(), fallback: z.string() });

export type Capability = "fast" | "vision" | "reasoning" | "fallback";

export function routedModel(capability: string) {
  const routes = routesSchema.parse(JSON.parse(env().ATLAS_MODEL_ROUTES_JSON));
  return routes[(capability.toLowerCase() as Capability) in routes ? capability.toLowerCase() as Capability : "fallback"];
}

export function usageChargeMicros(model: string, inputTokens: number, outputTokens: number) {
  const rate = ratesSchema.parse(JSON.parse(env().ATLAS_MODEL_RATES_JSON))[model];
  if (!rate) throw new Error(`No Atlas credit rate configured for ${model}`);
  return usageChargeForRate(rate, inputTokens, outputTokens);
}

export function usageChargeForRate(rate: { inputCreditsPerMillion: number; outputCreditsPerMillion: number }, inputTokens: number, outputTokens: number) {
  const credits = (inputTokens * rate.inputCreditsPerMillion + outputTokens * rate.outputCreditsPerMillion) / 1_000_000;
  return Math.max(1, Math.ceil(credits * 1_000_000));
}
