import { randomUUID } from "node:crypto";
import { z } from "zod";
import { env } from "./env";
import { openai } from "./clients";
import { usageChargeMicros } from "./billing";
import { reserveCredits, settleCredits } from "./ledger";
import { capabilitySchema, inferenceRiskSchema, latencyClassSchema, mediaPurposeSchema, MODEL_ROUTER_POLICY_VERSION, NoEligibleModelError, parseModelCatalog, selectModel } from "./model-router";
import { HttpError } from "./auth";

const contentPart = z.union([
  z.object({ type: z.literal("text"), text: z.string().max(20_000) }),
  z.object({
    type: z.literal("image_url"),
    image_url: z.object({
      url: z.string().max(1_100_000).regex(/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/, "Atlas Cloud accepts inline JPEG media only"),
    }),
  }),
]);
const toolCall = z.object({
  id: z.string().min(1).max(200),
  type: z.literal("function"),
  function: z.object({ name: z.string().min(1).max(100), arguments: z.string().max(16_384) }),
});
const toolDefinition = z.object({
  type: z.literal("function"),
  function: z.object({
    name: z.string().min(1).max(100),
    description: z.string().max(2_000).optional(),
    parameters: z.record(z.string(), z.unknown()),
  }),
});
const requestSchema = z.object({
  messages: z.array(z.object({
    role: z.enum(["system", "user", "assistant", "tool"]),
    content: z.union([z.string().max(32_000), z.array(contentPart).max(4), z.null()]),
    tool_call_id: z.string().max(200).optional(),
    tool_calls: z.array(toolCall).max(12).optional(),
  })).min(1).max(48),
  tools: z.array(toolDefinition).max(32).optional(),
});

export async function runInference(request: Request, userId: string) {
  const declaredLength = Number(request.headers.get("content-length") ?? 0);
  if (declaredLength > 1_600_000) return Response.json({ error: { code: "request_too_large", message: "Atlas Cloud accepts at most 1.6 MB per request" } }, { status: 413 });
  const body = requestSchema.parse(await request.json());
  const proposedRequestId = request.headers.get("x-atlas-request-id");
  const requestId = proposedRequestId && z.string().uuid().safeParse(proposedRequestId).success ? proposedRequestId : randomUUID();
  const capability = capabilitySchema.catch("fallback").parse(request.headers.get("x-atlas-capability") ?? "fallback");
  const risk = inferenceRiskSchema.catch("normal").parse(request.headers.get("x-atlas-risk") ?? "normal");
  const latencyClass = latencyClassSchema.catch("interactive").parse(request.headers.get("x-atlas-latency-class") ?? "interactive");
  const mediaPurpose = mediaPurposeSchema.catch("standard_vision").parse(request.headers.get("x-atlas-media-purpose") ?? "standard_vision");
  const hasImage = body.messages.some((message) => Array.isArray(message.content) && message.content.some((part) => part.type === "image_url"));
  const requiresTools = body.tools !== undefined && body.tools.length > 0;
  let selection: ReturnType<typeof selectModel>;
  try {
    selection = selectModel({ capability, risk, latencyClass, mediaPurpose, hasImage, requiresTools }, parseModelCatalog(env().ATLAS_MODEL_CATALOG_JSON));
  } catch (error) {
    if (error instanceof NoEligibleModelError) throw new HttpError(503, error.message, "no_eligible_model");
    throw error;
  }
  const model = selection.model.id;
  const routeRevision = `${env().ATLAS_MODEL_CATALOG_REVISION}/policy-${MODEL_ROUTER_POLICY_VERSION}`;
  console.info("atlas.model.selected", {
    requestId, model, routeRevision, capability, risk, latencyClass, mediaPurpose,
    hasImage, requiresTools, profile: selection.profile, score: selection.score, eligibleCount: selection.eligibleCount,
  });
  const reserved = env().ATLAS_MAX_REQUEST_CREDITS_MICROS;
  await reserveCredits(userId, requestId, reserved);
  const started = performance.now();
  try {
    const normalizedMessages = body.messages.map((message) => {
      if (Array.isArray(message.content)) {
        return { ...message, content: message.content.map((part) => part.type === "text"
          ? { type: "text", text: part.text }
          : { type: "image_url", image_url: { url: part.image_url.url, detail: selection.imageDetail } }) };
      }
      return message;
    });
    const response = await openai().chat.completions.create({
      model,
      messages: normalizedMessages as never,
      tools: body.tools as never,
      max_completion_tokens: selection.maxOutputTokens,
      reasoning_effort: selection.reasoningEffort === "none" ? "low" : selection.reasoningEffort as never,
    });
    const inputTokens = response.usage?.prompt_tokens ?? 0;
    const outputTokens = response.usage?.completion_tokens ?? 0;
    const charge = usageChargeMicros(model, inputTokens, outputTokens);
    await settleCredits({ userId, requestId, reserved, actual: charge, model, inputTokens, outputTokens, latencyMs: Math.round(performance.now() - started), status: "succeeded" });
    return Response.json({
      id: response.id, object: "chat.completion", created: response.created, model,
      choices: response.choices.map((choice) => ({ index: choice.index, finish_reason: choice.finish_reason, message: {
        role: "assistant", content: choice.message.content, tool_calls: choice.message.tool_calls,
      } })),
      usage: { prompt_tokens: inputTokens, completion_tokens: outputTokens, total_tokens: inputTokens + outputTokens },
    }, { headers: {
      "X-Atlas-Request-Id": requestId,
      "X-Atlas-Charge-Micros": String(charge),
      "X-Atlas-Model": model,
      "X-Atlas-Profile": selection.profile,
      "X-Atlas-Route-Reason": selection.reason,
      "X-Atlas-Route-Revision": routeRevision,
      "Cache-Control": "no-store",
    } });
  } catch (error) {
    await settleCredits({ userId, requestId, reserved, actual: 0, model, inputTokens: 0, outputTokens: 0, latencyMs: Math.round(performance.now() - started), status: "failed" });
    throw error;
  }
}
