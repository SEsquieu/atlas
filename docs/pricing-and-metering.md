# Pricing and metering framework

Status: planning framework for alpha instrumentation and later beta pricing. These are not public offers or promises.

## Principles

1. BYOI is free and first-class.
2. Managed inference is never advertised as unlimited.
3. Every provider call has a preflight reservation, hard output limit, loop limit, and attributable settlement.
4. Background perception has a separate budget from user-initiated work.
5. The user can understand why allowance was consumed.
6. Pricing changes through a versioned server policy, not an Android release.
7. Atlas records provider cost separately from customer charge before paid beta.

## Product ladder

| Offer | Planning price | Included managed allowance | Intended user |
| --- | ---: | ---: | --- |
| Open / BYOI | $0 | None | Developers running local, LAN, or their own cloud account |
| Closed Alpha | $0, invite only | Sponsor-funded hard allowance | Selected testers generating product evidence |
| Personal | $15/month | $6 retail inference allowance | One primary phone, ordinary conversational use |
| Personal Plus | $30/month | $18 retail inference allowance | Frequent vision/reasoning use and higher limits |
| Usage blocks | $5, $10, or $25 | Matching retail inference allowance | Occasional bursts without an automatic overage bill |
| Enterprise Pilot | Contracted | Explicit pooled cap | One process, site, or small station group with support |
| Enterprise Production | Platform + active device/station + usage | Contracted | Governed fleet and repeatable business workflows |

The consumer numbers are hypotheses. Do not publish them until alpha data establishes calls per active hour, image frequency, input growth, output length, route mix, provider cost, and support burden.

## Why subscription and usage are separate

The subscription pays for the operated product: authentication, routing, compatibility, streaming, metering, support, and continued development. The included allowance makes first use simple. Usage blocks prevent one heavy physical loop from consuming the margin for every light user.

BYOI users receive the complete runtime but pay their provider directly. They still create adoption, integrations, test coverage, and future enterprise pull-through.

## Metering model

The existing integer-microunit ledger remains the accounting primitive. Before paid beta, each inference record should retain:

- organization and actor;
- session and task-run correlation when available;
- capability, risk, latency class, and media purpose;
- selected provider/model and routing-policy revision;
- input, cached-input, and output units where the provider exposes them;
- provider cost in integer micros;
- customer charge in integer micros;
- subscription allowance versus purchased-block source;
- reservation, settlement, refund, and failure state; and
- request, first-token, and total latency.

Customer charge should initially follow:

```text
retail charge = max(request floor, provider cost × route multiplier)
```

The route multiplier covers payment fees, failed requests, routing infrastructure, support, and model-price volatility. Start planning around 2.0× landed inference cost, then tune with evidence. Do not hide an expensive reasoning call behind the same internal cost as a tiny heartbeat summary.

Provider cost and retail charge must never share one column. Margin cannot be measured if they are conflated.

## Closed-alpha sponsored budget

Recommended initial hard limits per invited account:

| Limit | Initial value |
| --- | ---: |
| Monthly provider-cost ceiling | $10 |
| Daily provider-cost ceiling | $1.50 |
| Single request reservation ceiling | $0.25 |
| Live Context monthly sub-budget | $2 |
| Agent model steps per user turn | 8 |
| Concurrent model requests | 1 per active session |

These are safety rails, not estimates of normal consumption. Start with 10–20 active testers, which bounds the theoretical monthly provider exposure to $100–$200. Alerts should fire at 50%, 80%, and 100% of both the user budget and total alpha budget.

At exhaustion, Atlas must:

1. stop before contacting the provider;
2. explain that the sponsored allowance is exhausted;
3. leave local sessions, memory, events, capture, STT, and TTS usable;
4. offer BYOI configuration; and
5. never silently bill or automatically purchase more usage.

## Live Context economics

Live Context should not mean periodic frontier inference. Capture and local scene-difference checks can run more often than semantic model review. Chargeable review occurs only after freshness, significance, cooldown, and budget policy all allow it.

Track these separately:

- heartbeat ticks;
- captures;
- locally rejected unchanged scenes;
- model-reviewed scenes;
- proactive responses; and
- user turns that successfully reuse heartbeat context.

The useful economic measure is not calls per hour. It is provider cost per useful user outcome and the explicit-turn latency avoided by maintained context.

## Enterprise pricing architecture

Do not price Atlas Enterprise primarily per human seat. Physical deployments derive value from active stations, devices, procedures, and completed task runs.

A production agreement should combine:

- an organization/platform minimum;
- active device or station fees;
- managed inference usage or customer-provided inference;
- optional retention/analytics volume;
- implementation and integration work; and
- support/SLA tier.

An enterprise BYOI deployment still pays for the control plane, policy, fleet operations, integrations, and support. Managed inference is optional consumption, not the entire enterprise product.

## Evidence required before open beta pricing

- Median and p95 provider cost per explicit turn
- Median and p95 provider cost per active hour
- Vision, reasoning, fast, and fallback route distribution
- Live Context cost and reuse benefit
- Tool-loop step distribution and failure rate
- Monthly active days and session duration
- Sponsored allowance exhaustion rate
- Provider failure/refund rate
- Gross margin under at least three usage profiles
- Payment, tax, refund, and stored-allowance policy review

Pricing becomes a launch decision only after these values come from real alpha loops.
