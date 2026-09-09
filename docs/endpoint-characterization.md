# Endpoint characterization and routing direction

## What exists

The Android app stores endpoint-declared booleans for vision, tools, and streaming; a reasoning toggle; a timeout; and a prompt profile. Users assign endpoint IDs to ordered `fast`, `vision`, `reasoning`, and `fallback` routes. `CapabilityRouter` filters incompatible endpoints and tries them in user order. It does not benchmark models, infer quality tiers, or dynamically reorder endpoints.

The managed gateway has a separate deterministic model scorer configured by quality, speed, and economy values. Those numbers are deployment inputs, not measurements produced by Atlas. Native Core records selected endpoint/model headers, latency, first-token latency, token usage, finish reason, and route revision when providers return them.

## Declaration is not reliability

`supportsVision=true` means an endpoint is configured to accept image input; it does not prove that a particular model/projector combination can understand an image. The same distinction applies to tools and streaming. Characterization should therefore retain both:

- declared capability: what configuration says should work;
- observed reliability: what versioned probes and real turns show actually worked.

Do not overwrite a user's declaration after one failure. Record evidence by endpoint configuration fingerprint, model identity when known, task dimension, prompt profile, and software version.

## Small future profile

An endpoint profile can grow alongside existing configuration without replacing routes:

| Dimension | Example evidence | Why it matters |
| --- | --- | --- |
| Recall/extraction | fixture assertions passed | Context retention is different from reasoning. |
| Formatting | exact/structured response compliance | Small local models may be useful when tightly constrained. |
| Tool use | valid call rate and argument/schema failures | Declared tool support is not reliable tool behavior. |
| Vision | image acceptance plus grounded-answer checks | Separates protocol compatibility from perception quality. |
| Entailment/reasoning | `unsupported_causal_bridge` and later fixtures | Prevents retrieval success from being mistaken for sound inference. |
| Latency | first token, total, timeout/late rate | Measured per prompt profile and modality. |
| Cost | provider charge or user-supplied estimate | Must remain optional for local/LAN endpoints. |
| Security/location | device, private network, remote; transport assessment | A routing constraint, not a quality score. |

Profiles should be inspectable evidence with sample count and recency, not one opaque rank.

## Routing direction

The eventual objective is the minimum purchased intelligence required to safely complete the turn. A grounded router would:

1. classify the turn's required abilities and consequence level deterministically;
2. exclude endpoints that fail hard modality, freshness, policy, privacy, or reliability floors;
3. choose among eligible endpoints using observed latency/cost preferences;
4. preserve the chosen profile revision and evidence in provenance;
5. avoid speculative retry when a provider may have accepted consequential work.

This is not implemented in v0.1. The current ordered route table is understandable and user-controlled; replacing it before the eval set has meaningful coverage would turn guesses into policy. The near-term extension is to export and aggregate existing attempt telemetry against named fixtures, then show characterization to the user before using it automatically.
