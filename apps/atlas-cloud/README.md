# Atlas Cloud gateway

Atlas Cloud is an optional, authenticated inference adapter for the Android runtime. It does not own Atlas sessions, observations, heartbeats, tools, or device permissions. The phone sends a single scoped inference request and keeps the authoritative physical-session state.

Direct bring-your-own inference remains fully usable without an Atlas account.

## What this service provides

- Supabase email/password authentication with short-lived user access tokens;
- server-held OpenAI credentials and deterministic, risk-aware model routing;
- personal and shared organization tenancy with membership-validated request scope;
- an atomic, integer-microunit organization credit ledger;
- reserve-before-call and settle-after-usage metering;
- Stripe Checkout for a recurring subscription allowance and one-time credit blocks;
- Stripe Billing Portal access and signature-verified, idempotent webhooks; and
- request IDs, model, token counts, latency, status, and charge records for reconciliation.

Atlas credits are an application accounting unit, not dollars or provider tokens. Product prices, included credits, the model catalog, and per-model credit rates are deployment configuration so changing providers does not require a client release.

The router filters on modality and supported reasoning effort, then scores eligible models using task-specific quality, speed, and economy measurements. Risk and latency class alter those weights. It also selects reasoning effort, output ceiling, and image detail. The response exposes the selected model, profile, and non-sensitive decision reason as headers so the device can preserve them in its event log. See [`../../docs/model-routing.md`](../../docs/model-routing.md).

## Local setup

1. Create a dedicated Supabase project and enable email/password authentication.
2. Apply every file in `supabase/migrations` in filename order with the Supabase CLI.
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

`ATLAS_MODEL_CATALOG_JSON` initially enables only GPT-5.6 Luna. Catalog scores must come from repeatable Atlas evals, not model branding. Enabling a more expensive model is a deployment decision and does not require an Android release.

## Billing invariant

Each inference first resolves the authenticated principal into an active organization membership. Omitting `X-Atlas-Organization-Id` selects the caller's personal organization. Atlas then reserves the deployment's maximum per-request credit amount from that organization's balance inside one database transaction. Usage retains both organization and actor attribution. If funds are insufficient, the provider is never called. After the provider returns, Atlas settles the reservation against actual input/output usage and refunds the difference. Provider failures settle to zero. Stripe grants use unique event references, so webhook retries do not mint credits twice.

Before a public beta, add rate limiting/abuse controls, App Check or device attestation, transactional email branding, tax/refund policy, stuck-reservation reconciliation, operator dashboards/alerts, and end-to-end test-mode Stripe tests. This directory is deployable plumbing, not a claim that a production billing service is already operating.
