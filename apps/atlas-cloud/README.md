# Atlas Cloud gateway

Atlas Cloud is an optional, authenticated inference adapter for the Android runtime. It does not own Atlas sessions, observations, heartbeats, tools, or device permissions. The phone sends a single scoped inference request and keeps the authoritative physical-session state.

Direct bring-your-own inference remains fully usable without an Atlas account.

## What this service provides

- Supabase email/password authentication with short-lived user access tokens;
- server-held OpenAI credentials and capability-to-model routing;
- an atomic, integer-microunit Atlas credit ledger;
- reserve-before-call and settle-after-usage metering;
- Stripe Checkout for a recurring subscription allowance and one-time credit blocks;
- Stripe Billing Portal access and signature-verified, idempotent webhooks; and
- request IDs, model, token counts, latency, status, and charge records for reconciliation.

Atlas credits are an application accounting unit, not dollars or provider tokens. Product prices, included credits, route models, and per-model credit rates are deployment configuration so changing providers does not require a client release.

## Local setup

1. Create a dedicated Supabase project and enable email/password authentication.
2. Apply `supabase/migrations/20260903190000_atlas_billing.sql` with the Supabase CLI or SQL editor.
3. Create one recurring Stripe Price and one one-time Stripe Price.
4. Copy `.env.example` to `.env.local` and fill every value. Keep the Supabase service-role key, OpenAI key, Stripe key, and webhook secret server-only.
5. Run `npm ci`, `npm test`, and `npm run dev`.
6. Forward Stripe events to `/api/stripe/webhook`. Subscribe to `checkout.session.completed`, `invoice.payment_succeeded`, `customer.subscription.updated`, and `customer.subscription.deleted`.

The Android app is configured at build time:

```bash
cd ../atlas-android
./gradlew assembleDebug \
  -PATLAS_GATEWAY_URL=https://your-gateway.example \
  -PSUPABASE_URL=https://PROJECT.supabase.co \
  -PSUPABASE_PUBLISHABLE_KEY=sb_publishable_REPLACE_ME
```

The publishable Supabase key is intentionally client-visible. Never put the service-role key or an inference-provider key in the APK.

## Billing invariant

Each inference reserves the deployment's maximum per-request credit amount inside one database transaction. If funds are insufficient, the provider is never called. After the provider returns, Atlas settles the reservation against actual input/output usage and refunds the difference. Provider failures settle to zero. Stripe grants use unique event references, so webhook retries do not mint credits twice.

Before a public beta, add rate limiting/abuse controls, App Check or device attestation, transactional email branding, tax/refund policy, stuck-reservation reconciliation, operator dashboards/alerts, and end-to-end test-mode Stripe tests. This directory is deployable plumbing, not a claim that a production billing service is already operating.
