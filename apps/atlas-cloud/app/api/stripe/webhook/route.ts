import { errorResponse } from "@/src/auth";
import { stripe } from "@/src/clients";
import { env } from "@/src/env";
import { fulfillStripeEvent } from "@/src/stripe-billing";

export async function POST(request: Request) {
  try {
    const signature = request.headers.get("stripe-signature");
    if (!signature) return new Response("Missing signature", { status: 400 });
    const event = stripe().webhooks.constructEvent(await request.text(), signature, env().STRIPE_WEBHOOK_SECRET);
    await fulfillStripeEvent(event);
    return Response.json({ received: true });
  } catch (error) { return errorResponse(error); }
}
