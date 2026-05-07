# Context, Memory, Spillover, and Sidecars

Status: architectural shape captured 2026-05-06. Some pieces are MVP-relevant now; others are intentionally deferred but should shape interfaces so Atlas does not paint itself into a corner.

## Core Frame

Atlas should not be modeled as “an LLM with a camera.” It should be modeled as a situated runtime that maintains claims about reality over time, separates physical task state from unrelated assistant work, and admits provider/runtime suggestions only through Atlas policy.

The central invariants are:

- Physical session memory is owned by Atlas Core.
- Provider runtimes suggest meaning; Atlas owns physical truth.
- Spillover must not pollute physical session memory.
- Background enrichment must be bounded, optional, and explicitly routed.

## MVP vs Deferred

### MVP-shaping now

These concepts should influence current type/event boundaries even if the first implementation is simple:

- context lanes: physical context, supporting context, spillover
- dynamic heartbeat cadence hooks
- freshness decay inputs and policy outputs
- provider sideband result shape, even if most fields are empty at first
- admission policy for provider memory/tool/artifact suggestions

### Stub/defer after solid MVP

These should be documented and kept compatible, but not required for the first live Android/OpenClaw loop:

- full weighted-belief memory store
- sophisticated truth-state transitions and decay policies
- spillover delegation to external assistant systems
- parallel inference sidecar scheduler
- multi-sidecar budget arbitration
- complex memory compression or retrieval

## 1. Dynamic Heartbeat Cadence

Heartbeat cadence should be a policy output, not a fixed timer.

Initial cadence modes:

| Mode | Typical signal | Behavior |
| --- | --- | --- |
| `idle` | no active task, no recent user turn | very slow heartbeat |
| `stable-scene` | stationary, high confidence, low change | slower heartbeat |
| `active-task` | current objective/user loop is active | moderate heartbeat |
| `unstable-scene` | walking/turning/transitional/low confidence | faster heartbeat |
| `high-risk` | safety-critical visual/context task | fastest allowed cadence, plus stricter refresh gates |

The first implementation can map session state to a suggested next heartbeat delay:

```ts
type HeartbeatCadenceMode =
  | 'idle'
  | 'stable-scene'
  | 'active-task'
  | 'unstable-scene'
  | 'high-risk';

interface HeartbeatCadenceDecision {
  mode: HeartbeatCadenceMode;
  nextDelayMs: number;
  reason: string;
}
```

This pairs with visual freshness. Fresh, stable context slows the loop. Unstable or decaying context speeds it up.

The loop should aim to refresh before context becomes stale. Stale is a hard-deadline/fallback condition; the normal heartbeat trigger should be an earlier refresh deadline based on weighted freshness, recent refresh latency, and a safety margin. Later policy can expose this as a speed-vs-performance mode: speed favors context correctness and low ask latency at the cost of battery/heat, while performance favors device health and accepts more stale-context fallback.

## 2. Freshness Decay with Context-Aware Multipliers

Freshness should decay according to risk and context, not just wall-clock time.

Useful multipliers/signals:

- motion state
- stability
- confidence
- scene volatility
- active task type
- user question use case
- risk level
- source quality
- observation age

Examples:

- a stable shelf-label photo while stationary may remain useful for minutes
- an “am I in the right place?” navigation answer decays quickly while walking
- a high-risk visual confirmation should require fresh context regardless of age

MVP implication: keep the existing visual freshness policy tunable and make sure observations preserve enough metadata to recalculate decay later.

## 3. Memory as Weighted Belief

Atlas memory should evolve toward weighted claims about reality over time, not raw text blobs.

Core insight:

> memory = claims about reality over time

Future memory items should be able to carry:

- importance
- confidence
- freshness
- relevance
- truth status
- decay policy
- scope
- source/provenance
- observation or event references

Sketch:

```ts
type TruthStatus = 'current' | 'stale' | 'historical' | 'archived';

interface BeliefClaim {
  id: string;
  claim: string;
  scope: 'turn' | 'session' | 'task' | 'user' | 'world';
  source: 'device' | 'perception-analyzer' | 'provider-suggestion' | 'user' | 'derived';
  importance: number;
  confidence: number;
  freshness: number;
  relevance: string[];
  truthStatus: TruthStatus;
  decayPolicy: string;
  observedAt: string;
  lastEvaluatedAt?: string;
  evidenceEventIds?: string[];
  supersededBy?: string;
}
```

Truth should transition over time:

```text
current → stale → historical → archived
```

MVP implication: do not prematurely collapse session memory into unstructured summaries. Preserve event provenance and keep a lane for future `beliefCandidate`/`beliefClaim` records.

## 4. Physical Context vs Supporting Context vs Spillover

Atlas should keep distinct context lanes.

### Physical Context

Generated through Atlas devices and perception loops.

Examples:

- current image observation
- location observation
- motion state
- scene stability/confidence
- perceived object/scene/OCR analysis

### Supporting Context

Provider-generated or tool-fetched artifacts that support the active physical task.

Examples:

- floorplans
- manuals
- maps
- docs
- OCR results
- shopping list or repair procedure relevant to the active session

### Spillover

General assistant tasks unrelated to the active physical session.

Examples:

- email requests
- reminders
- calendar changes
- unrelated side conversations
- coding questions while the physical session is active

Core invariant:

> Spillover must not pollute physical session memory.

Stronger operational rule:

> Only context explicitly admitted through Atlas policy may enter physical session memory.

## 5. Spillover Routing / Side Request Quarantine

Atlas should eventually detect unrelated side requests, separate them from the physical loop, and optionally delegate them back to a provider/runtime or outside assistant system.

Example user turn:

> “What am I holding? Also remind me to email Sarah.”

Expected routing:

- physical loop handles: “What am I holding?”
- spillover router quarantines: “remind me to email Sarah”
- session memory may record that a mixed-intent turn occurred
- session memory must not absorb Sarah/email/reminder as physical task state

Potential future event/object:

```ts
type SpilloverStatus = 'detected' | 'quarantined' | 'delegated' | 'completed' | 'failed' | 'ignored';

interface SpilloverRequest {
  id: string;
  sourceTurnEventId: string;
  text: string;
  status: SpilloverStatus;
  reason: string;
  suggestedRoute?: 'provider' | 'assistant' | 'scheduler' | 'ignore';
  admittedToPhysicalSession: false;
}
```

MVP implication: even before delegation exists, mixed-intent turns should have a place to record quarantined side requests without contaminating physical context.

## 6. Structured Provider Sideband Contracts

Provider adapters may emit structured metadata alongside conversational responses.

Potential normalized result shape:

```ts
interface ProviderSidebandResult {
  responseText: string;
  artifacts?: SupportingArtifactCandidate[];
  routeHints?: RouteHint[];
  beliefCandidates?: BeliefCandidate[];
  toolCalls?: ToolCallCandidate[];
}
```

Atlas behavior:

- accept provider suggestions
- validate independently
- decide what enters session state
- keep provider-runtime memory separate from Atlas session truth

Core invariant:

> Providers suggest meaning. Atlas owns physical truth.

Admission examples:

- `responseText` may become user-visible speech
- `artifacts` may become supporting context if relevant to the active physical task
- `beliefCandidates` may become weighted claims only after Core policy admits them
- `toolCalls` may execute only through Atlas tool/action policy
- `routeHints` are advisory only

## 7. Budgeted Inference Sidecars

Atlas may eventually run lightweight parallel sidecars for context enrichment.

Examples:

- scene summarization
- path mapping
- hazard detection
- memory compression
- context enrichment
- OCR cleanup

Design principle:

> Sidecars enrich context but do not block the primary response path.

Each sidecar should declare:

- reason
- token budget
- latency budget
- priority
- explicit write destination
- permission to be ignored

Sketch:

```ts
interface InferenceSidecarJob {
  id: string;
  reason: string;
  priority: 'low' | 'normal' | 'high';
  tokenBudget: number;
  latencyBudgetMs: number;
  writeDestination: 'physical-context' | 'supporting-context' | 'belief-candidates' | 'audit-only';
  mayBeIgnored: true;
}
```

Core invariant:

No sidecar runs without:

1. a reason
2. a budget
3. a destination
4. permission to be ignored

MVP implication: leave room in the event model for async enrichment results, but do not let sidecars become required for the first response path.

