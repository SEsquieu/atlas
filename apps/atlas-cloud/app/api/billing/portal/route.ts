import { requireUser, errorResponse } from "@/src/auth";
import { billingAccount } from "@/src/stripe-billing";
import { stripe } from "@/src/clients";
import { env } from "@/src/env";

export async function POST(request: Request) {
  try {
    const user = await requireUser(request);
    const portal = await stripe().billingPortal.sessions.create({ customer: await billingAccount(user.id, user.email), return_url: env().NEXT_PUBLIC_APP_URL });
    return Response.json({ url: portal.url });
  } catch (error) { return errorResponse(error); }
}
