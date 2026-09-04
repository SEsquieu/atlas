import { randomUUID } from "node:crypto";
import { z } from "zod";
import { env } from "./env";
import { openai } from "./clients";
import { routedModel, usageChargeMicros } from "./billing";
import { reserveCredits, settleCredits } from "./ledger";
import { HttpError, type AtlasRequestContext } from "./auth";

const contentPart = z.union([
  z.object({ type: z.literal("text"), text: z.string().max(20_000) }),
  z.object({
    type: z.literal("image_url"),
    image_url: z.object({
      url: z.string().max(1_100_000).regex(/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/, "Atlas Cloud accepts inline JPEG media only"),
    }),
  }),
]);
const requestSchema = z.object({
  messages: z.array(z.object({ role: z.enum(["system", "user", "assistant"]), content: z.union([z.string().max(20_000), z.array(contentPart).max(4)]) })).min(1).max(24),
});

export async function runInference(request: Request, context: AtlasRequestContext) {
  const declaredLength = Number(request.headers.get("content-length") ?? 0);
  if (declaredLength > 1_600_000) return Response.json({ error: { code: "request_too_large", message: "Atlas Cloud accepts at most 1.6 MB per request" } }, { status: 413 });
  const body = requestSchema.parse(await request.json());
  const proposedRequestId = request.headers.get("x-atlas-request-id");
  const requestId = proposedRequestId && z.string().uuid().safeParse(proposedRequestId).success ? proposedRequestId : randomUUID();
  const capability = request.headers.get("x-atlas-capability") ?? "fallback";
  const sessionId = optionalUuidHeader(request, "x-atlas-session-id");
  const taskRunId = optionalUuidHeader(request, "x-atlas-task-run-id");
  const mediaPurpose = request.headers.get("x-atlas-media-purpose") ?? "standard_vision";
  const model = routedModel(capability);
  const reserved = env().ATLAS_MAX_REQUEST_CREDITS_MICROS;
  await reserveCredits({ organizationId: context.organizationId, userId: context.user.id, requestId, amount: reserved, sessionId, taskRunId, capability });
  const started = performance.now();
  try {
    const system = body.messages.filter((message) => message.role === "system").map((message) => typeof message.content === "string" ? message.content : "").join("\n\n");
    const turns = body.messages.filter((message) => message.role !== "system").map((message) => ({
      role: message.role,
      content: typeof message.content === "string" ? message.content : message.content.map((part) => part.type === "text"
        ? { type: "input_text", text: part.text }
        : { type: "input_image", image_url: part.image_url.url, detail: mediaPurpose === "detail_vision" ? "high" : "low" }),
    }));
    const response = await openai().responses.create({ model, instructions: system || undefined, input: turns as never, max_output_tokens: 600 });
    const inputTokens = response.usage?.input_tokens ?? 0;
    const outputTokens = response.usage?.output_tokens ?? 0;
    const charge = usageChargeMicros(model, inputTokens, outputTokens);
    await settleCredits({ organizationId: context.organizationId, userId: context.user.id, requestId, reserved, actual: charge, model, inputTokens, outputTokens, latencyMs: Math.round(performance.now() - started), status: "succeeded" });
    return Response.json({
      id: response.id, object: "chat.completion", created: Math.floor(Date.now() / 1000), model,
      choices: [{ index: 0, finish_reason: "stop", message: { role: "assistant", content: response.output_text } }],
      usage: { prompt_tokens: inputTokens, completion_tokens: outputTokens, total_tokens: inputTokens + outputTokens },
    }, { headers: { "X-Atlas-Request-Id": requestId, "X-Atlas-Organization-Id": context.organizationId, "X-Atlas-Charge-Micros": String(charge), "Cache-Control": "no-store" } });
  } catch (error) {
    await settleCredits({ organizationId: context.organizationId, userId: context.user.id, requestId, reserved, actual: 0, model, inputTokens: 0, outputTokens: 0, latencyMs: Math.round(performance.now() - started), status: "failed" });
    throw error;
  }
}

function optionalUuidHeader(request: Request, name: string): string | undefined {
  const value = request.headers.get(name);
  if (!value) return undefined;
  const parsed = z.string().uuid().safeParse(value);
  if (!parsed.success) throw new HttpError(400, `${name} must be a UUID`, "invalid_scope_id");
  return parsed.data;
}
