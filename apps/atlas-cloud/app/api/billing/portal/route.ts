import { requireAtlasContext, requireOrganizationRole, errorResponse } from "@/src/auth";
import { billingAccount } from "@/src/stripe-billing";
import { stripe } from "@/src/clients";
import { env } from "@/src/env";

export async function POST(request: Request) {
  try {
    const context = await requireAtlasContext(request);
    requireOrganizationRole(context, ["owner", "admin", "billing"]);
    const portal = await stripe().billingPortal.sessions.create({ customer: await billingAccount(context), return_url: env().NEXT_PUBLIC_APP_URL });
    return Response.json({ url: portal.url });
  } catch (error) { return errorResponse(error); }
}
