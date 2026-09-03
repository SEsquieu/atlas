# Model routing policy

Atlas has two routing layers with deliberately different responsibilities.

1. Atlas Core chooses a provider endpoint from the user's capability routes. It owns the request's physical context, freshness decision, risk, latency class, and endpoint failover.
2. A provider endpoint may choose a concrete model. Atlas Cloud does this for managed inference, but a LAN or on-device endpoint may implement the same headers without exposing model names to Core.

The model must never acquire ownership of the physical session.

## Selection contract

Core sends these routing inputs to compatible endpoints:

| Input | Meaning |
| --- | --- |
| `X-Atlas-Capability` | `fast`, `vision`, `reasoning`, or `fallback` task shape |
| `X-Atlas-Risk` | `normal`, `elevated`, or `safety_critical` consequence level |
| `X-Atlas-Latency-Class` | `latency_critical`, `interactive`, or `background` timing preference |
| `X-Atlas-Media-Purpose` | `heartbeat`, `standard_vision`, or `detail_vision` image intent |

Atlas Cloud first removes ineligible models: disabled entries, models without image input when an image is present, models that cannot run the required reasoning effort, and models below the measured quality floor for elevated-risk work. It then computes a deterministic weighted score from:

- task-specific quality measured by Atlas evals;
- observed speed; and
- deployment economics.

Risk and latency class change the weights. Safety-critical requests strongly favor measured quality. Latency-critical requests favor speed. Background work favors economy. Stable model ID ordering breaks exact ties, so the same catalog and request always produce the same result.

This is not an LLM-powered router. Routing adds no inference call, hidden prompt, or extra model latency.

## Profiles

| Intent | Reasoning effort | Output ceiling | Image detail |
| --- | --- | ---: | --- |
| Fast, normal | `none` | 160 | low |
| Vision, normal | `low` | 600 | low unless detail capture |
| Reasoning, normal | `medium` | 2,000 | low |
| Safety-critical vision | `medium` | 1,000 | high for detail capture |
| Safety-critical reasoning | `high` | 5,000 | low |

These are request budgets, not quality claims. Freshness and safety checks remain Core policy. A stronger model must not turn stale or unavailable physical evidence into permission to answer.

## Catalog calibration

`ATLAS_MODEL_CATALOG_JSON` is deployment configuration. Each enabled model declares supported modalities/efforts, Atlas credit rates, and normalized `quality`, `speed`, and `economy` scores from 0 to 1.

Do not fill the scores from vendor labels or intuition. Start with one model, as the example deployment does with GPT-5.6 Luna. Add another model only after a replayable Atlas evaluation set measures it on the route where it is intended to compete. Recalibration should preserve the eval results, model snapshot where available, sample count, latency distribution, failure rate, token usage, and catalog revision.

The initial catalog intentionally contains only Luna. It exercises different reasoning and media profiles without pretending Atlas has evidence that a more expensive model improves this physical loop. The router is ready for additional models when that evidence exists.

## Observability

Atlas Cloud returns:

- `X-Atlas-Model`;
- `X-Atlas-Profile`; and
- `X-Atlas-Route-Reason`; and
- `X-Atlas-Route-Revision`.

The Android runtime stores these values in its `provider.responded` event alongside endpoint, latency, and degraded state. The revision combines a deployment-controlled catalog revision with the code-level policy version, making a historical choice reproducible. The managed ledger independently records the concrete model, token counts, latency, status, and charge.

The route reason contains policy inputs and numeric weights only; it must never include prompts, image data, user identifiers, or model chain-of-thought.

## Failure boundary

Core retains ordered endpoint failover. Atlas Cloud currently makes one metered provider attempt after reserving credits. It does not silently issue a second paid model call, because a failed provider response can have ambiguous billing. Model-attempt failover belongs behind an attempt-level usage ledger and explicit retry policy, not inside the scorer.
