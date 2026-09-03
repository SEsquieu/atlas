import { z } from "zod";
import { requireUser, errorResponse } from "@/src/auth";
import { createCheckout } from "@/src/stripe-billing";

export async function POST(request: Request) {
  try {
    const user = await requireUser(request);
    const { product } = z.object({ product: z.enum(["subscription", "credit_block"]) }).parse(await request.json());
    const checkout = await createCheckout(user.id, user.email, product);
    return Response.json({ url: checkout.url });
  } catch (error) { return errorResponse(error); }
}
