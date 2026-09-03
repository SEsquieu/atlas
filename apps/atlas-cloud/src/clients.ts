import OpenAI from "openai";
import Stripe from "stripe";
import { createClient } from "@supabase/supabase-js";
import { env } from "./env";

export function adminDb() {
  const e = env();
  return createClient(e.NEXT_PUBLIC_SUPABASE_URL, e.SUPABASE_SERVICE_ROLE_KEY, { auth: { persistSession: false, autoRefreshToken: false } });
}

export function openai() { return new OpenAI({ apiKey: env().OPENAI_API_KEY }); }
export function stripe() { return new Stripe(env().STRIPE_SECRET_KEY, { apiVersion: "2026-08-26.dahlia" }); }
