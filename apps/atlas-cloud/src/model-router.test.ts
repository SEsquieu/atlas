import assert from "node:assert/strict";
import test from "node:test";
import { ModelCatalogEntry, parseModelCatalog, RoutingIntent, selectModel } from "./model-router";

const baseIntent: RoutingIntent = {
  capability: "fast",
  risk: "normal",
  latencyClass: "interactive",
  mediaPurpose: "standard_vision",
  hasImage: false,
};

function model(id: string, values: Partial<ModelCatalogEntry> = {}): ModelCatalogEntry {
  return {
    id,
    enabled: true,
    supportsVision: true,
    supportsTools: true,
    reasoningEfforts: ["none", "low", "medium", "high"],
    quality: { fast: 0.8, vision: 0.8, reasoning: 0.8, fallback: 0.8 },
    speed: 0.8,
    economy: 0.8,
    inputCreditsPerMillion: 1,
    outputCreditsPerMillion: 5,
    ...values,
  };
}

test("latency-critical work favors the measured faster model", () => {
  const quick = model("quick", { speed: 1, economy: 0.8, quality: { fast: 0.75, vision: 0.75, reasoning: 0.65, fallback: 0.75 } });
  const deep = model("deep", { speed: 0.4, economy: 0.3, quality: { fast: 1, vision: 1, reasoning: 1, fallback: 1 } });
  const result = selectModel({ ...baseIntent, latencyClass: "latency_critical" }, [deep, quick]);
  assert.equal(result.model.id, "quick");
  assert.equal(result.reasoningEffort, "none");
  assert.equal(result.maxOutputTokens, 160);
});
test("reasoning and safety-critical work favors measured quality", () => {
  const result = selectModel({ ...baseIntent, capability: "reasoning", risk: "safety_critical" }, [
    model("quick", { speed: 1, quality: { fast: 0.9, vision: 0.7, reasoning: 0.6, fallback: 0.8 } }),
    model("deep", { speed: 0.3, economy: 0.2, quality: { fast: 0.9, vision: 1, reasoning: 1, fallback: 0.9 } }),
  ]);
  assert.equal(result.model.id, "deep");
  assert.equal(result.reasoningEffort, "high");
  assert.equal(result.maxOutputTokens, 5_000);
});

test("background work favors economy", () => {
  const result = selectModel({ ...baseIntent, latencyClass: "background" }, [
    model("cheap", { economy: 1, speed: 0.6, quality: { fast: 0.75, vision: 0.75, reasoning: 0.75, fallback: 0.75 } }),
    model("premium", { economy: 0.1, speed: 0.8, quality: { fast: 1, vision: 1, reasoning: 1, fallback: 1 } }),
  ]);
  assert.equal(result.model.id, "cheap");
});

test("hard constraints reject models without the required modality or effort", () => {
  const result = selectModel({ ...baseIntent, capability: "vision", risk: "elevated", hasImage: true }, [
    model("text-only", { supportsVision: false, speed: 1 }),
    model("no-medium", { reasoningEfforts: ["none", "low"], speed: 1 }),
    model("eligible"),
  ]);
  assert.equal(result.model.id, "eligible");
  assert.equal(result.eligibleCount, 1);
});

test("tool-capable work excludes models without function calling", () => {
  const result = selectModel({ ...baseIntent, requiresTools: true }, [
    model("fast-text", { supportsTools: false, speed: 1 }),
    model("agent", { supportsTools: true }),
  ]);
  assert.equal(result.model.id, "agent");
  assert.equal(result.eligibleCount, 1);
});

test("consequential work fails closed below its measured quality floor", () => {
  assert.throws(() => selectModel({ ...baseIntent, capability: "vision", risk: "safety_critical", hasImage: true }, [
    model("unproven", { quality: { fast: 0.9, vision: 0.79, reasoning: 0.9, fallback: 0.9 } }),
  ]), /quality>=0.8/);
});

test("detail media selects high image detail and ties are deterministic", () => {
  const result = selectModel({ ...baseIntent, capability: "vision", hasImage: true, mediaPurpose: "detail_vision" }, [model("zeta"), model("alpha")]);
  assert.equal(result.model.id, "alpha");
  assert.equal(result.imageDetail, "high");
  assert.match(result.reason, /eligible=2/);
});

test("catalog validation rejects duplicate model identifiers", () => {
  const duplicate = JSON.stringify([model("same"), model("same")]);
  assert.throws(() => parseModelCatalog(duplicate), /Duplicate model id/);
});
