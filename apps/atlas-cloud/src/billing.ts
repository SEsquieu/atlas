import { env } from "./env";
import { parseModelCatalog } from "./model-router";

export function usageChargeMicros(model: string, inputTokens: number, outputTokens: number) {
  const rate = parseModelCatalog(env().ATLAS_MODEL_CATALOG_JSON).find((candidate) => candidate.id === model);
  if (!rate) throw new Error(`No Atlas credit rate configured for ${model}`);
  return usageChargeForRate(rate, inputTokens, outputTokens);
}

export function usageChargeForRate(rate: { inputCreditsPerMillion: number; outputCreditsPerMillion: number }, inputTokens: number, outputTokens: number) {
  const credits = (inputTokens * rate.inputCreditsPerMillion + outputTokens * rate.outputCreditsPerMillion) / 1_000_000;
  return Math.max(1, Math.ceil(credits * 1_000_000));
}
