import { z } from "zod";

const schema = z.object({
  NEXT_PUBLIC_APP_URL: z.url(),
  NEXT_PUBLIC_SUPABASE_URL: z.url(),
  NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY: z.string().min(1),
  SUPABASE_SERVICE_ROLE_KEY: z.string().min(1),
  OPENAI_API_KEY: z.string().min(1),
  STRIPE_SECRET_KEY: z.string().min(1),
  STRIPE_WEBHOOK_SECRET: z.string().min(1),
  STRIPE_SUBSCRIPTION_PRICE_ID: z.string().min(1),
  STRIPE_CREDIT_BLOCK_PRICE_ID: z.string().min(1),
  ATLAS_SUBSCRIPTION_CREDITS_MICROS: z.coerce.number().int().positive(),
  ATLAS_CREDIT_BLOCK_CREDITS_MICROS: z.coerce.number().int().positive(),
  ATLAS_MAX_REQUEST_CREDITS_MICROS: z.coerce.number().int().positive(),
  ATLAS_MODEL_CATALOG_JSON: z.string(),
  ATLAS_MODEL_CATALOG_REVISION: z.string().min(1),
});

export function env() { return schema.parse(process.env); }
