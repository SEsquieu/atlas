import type Stripe from "stripe";
import { adminDb, stripe } from "./clients";
import { env } from "./env";

export async function billingAccount(userId: string, email?: string) {
  const db = adminDb();
  const { data } = await db.from("atlas_billing_accounts").select("stripe_customer_id").eq("user_id", userId).maybeSingle();
  if (data?.stripe_customer_id) return data.stripe_customer_id as string;
  const customer = await stripe().customers.create({ email, metadata: { atlas_user_id: userId } });
  const { error } = await db.from("atlas_billing_accounts").upsert({ user_id: userId, stripe_customer_id: customer.id }, { onConflict: "user_id" });
  if (error) throw new Error(`Could not attach Stripe customer: ${error.message}`);
  return customer.id;
}

export async function createCheckout(userId: string, email: string | undefined, product: "subscription" | "credit_block") {
  const e = env();
  const customer = await billingAccount(userId, email);
  const subscription = product === "subscription";
  return stripe().checkout.sessions.create({
    customer, mode: subscription ? "subscription" : "payment",
    line_items: [{ price: subscription ? e.STRIPE_SUBSCRIPTION_PRICE_ID : e.STRIPE_CREDIT_BLOCK_PRICE_ID, quantity: 1 }],
    success_url: `${e.NEXT_PUBLIC_APP_URL}/billing/success`, cancel_url: `${e.NEXT_PUBLIC_APP_URL}/billing/canceled`,
    client_reference_id: userId,
    metadata: { atlas_user_id: userId, product, credit_micros: String(subscription ? e.ATLAS_SUBSCRIPTION_CREDITS_MICROS : e.ATLAS_CREDIT_BLOCK_CREDITS_MICROS) },
    subscription_data: subscription ? { metadata: { atlas_user_id: userId } } : undefined,
  });
}

export async function fulfillStripeEvent(event: Stripe.Event) {
  const db = adminDb();
  const { data: existing } = await db.from("atlas_stripe_events").select("event_id").eq("event_id", event.id).maybeSingle();
  if (existing) return;
  if (event.type === "checkout.session.completed") {
    const session = event.data.object as Stripe.Checkout.Session;
    if (session.mode === "payment" && session.payment_status === "paid" && session.metadata?.atlas_user_id) {
      await grant(session.metadata.atlas_user_id, Number(session.metadata.credit_micros), `stripe:${event.id}`, "credit_block");
    }
  } else if (event.type === "invoice.payment_succeeded") {
    const invoice = event.data.object as Stripe.Invoice;
    const subscriptionId = typeof invoice.parent?.subscription_details?.subscription === "string" ? invoice.parent.subscription_details.subscription : null;
    if (subscriptionId && (invoice.billing_reason === "subscription_create" || invoice.billing_reason === "subscription_cycle")) {
      const subscription = await stripe().subscriptions.retrieve(subscriptionId);
      const userId = subscription.metadata.atlas_user_id;
      if (userId) {
        await grant(userId, env().ATLAS_SUBSCRIPTION_CREDITS_MICROS, `stripe:${event.id}`, "subscription_allowance");
        await db.from("atlas_billing_accounts").update({ subscription_status: subscription.status, stripe_subscription_id: subscription.id, subscription_period_end: new Date(subscription.items.data[0]?.current_period_end * 1000).toISOString() }).eq("user_id", userId);
      }
    }
  } else if (event.type === "customer.subscription.deleted" || event.type === "customer.subscription.updated") {
    const subscription = event.data.object as Stripe.Subscription;
    if (subscription.metadata.atlas_user_id) await db.from("atlas_billing_accounts").update({ subscription_status: subscription.status }).eq("user_id", subscription.metadata.atlas_user_id);
  }
  const { error } = await db.from("atlas_stripe_events").insert({ event_id: event.id, event_type: event.type });
  if (error?.code !== "23505") throw new Error(error?.message);
}

async function grant(userId: string, amount: number, reference: string, kind: string) {
  const { error } = await adminDb().rpc("atlas_grant_credits", { p_user_id: userId, p_amount: amount, p_reference: reference, p_kind: kind });
  if (error) throw new Error(error.message);
}
