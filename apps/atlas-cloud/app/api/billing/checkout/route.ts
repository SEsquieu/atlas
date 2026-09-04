import { z } from "zod";
import { requireAtlasContext, requireOrganizationRole, errorResponse } from "@/src/auth";
import { createCheckout } from "@/src/stripe-billing";

export async function POST(request: Request) {
  try {
    const context = await requireAtlasContext(request);
    requireOrganizationRole(context, ["owner", "admin", "billing"]);
    const { product } = z.object({ product: z.enum(["subscription", "credit_block"]) }).parse(await request.json());
    const checkout = await createCheckout(context, product);
    return Response.json({ url: checkout.url });
  } catch (error) { return errorResponse(error); }
}
