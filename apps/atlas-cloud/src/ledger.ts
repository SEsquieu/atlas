import { adminDb } from "./clients";
import { HttpError } from "./auth";

export async function reserveCredits(userId: string, requestId: string, amount: number) {
  const { data, error } = await adminDb().rpc("atlas_reserve_credits", { p_user_id: userId, p_request_id: requestId, p_amount: amount });
  if (error) throw new Error(`Credit reservation failed: ${error.message}`);
  if (!data) throw new HttpError(402, "Not enough Atlas credits", "insufficient_credits");
}

export async function settleCredits(args: { userId: string; requestId: string; reserved: number; actual: number; model: string; inputTokens: number; outputTokens: number; latencyMs: number; status: string }) {
  const { error } = await adminDb().rpc("atlas_settle_credits", {
    p_user_id: args.userId, p_request_id: args.requestId, p_reserved: args.reserved, p_actual: args.actual,
    p_model: args.model, p_input_tokens: args.inputTokens, p_output_tokens: args.outputTokens,
    p_latency_ms: args.latencyMs, p_status: args.status,
  });
  if (error) throw new Error(`Credit settlement failed: ${error.message}`);
}

export async function balance(userId: string) {
  const { error: createError } = await adminDb().from("atlas_billing_accounts").upsert(
    { user_id: userId },
    { onConflict: "user_id", ignoreDuplicates: true },
  );
  if (createError) throw new Error(`Balance initialization failed: ${createError.message}`);
  const { data, error } = await adminDb().from("atlas_billing_accounts").select("balance_micros,subscription_status,subscription_period_end").eq("user_id", userId).single();
  if (error) throw new Error(`Balance lookup failed: ${error.message}`);
  return data;
}
