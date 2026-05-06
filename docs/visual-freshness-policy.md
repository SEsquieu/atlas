# Visual Freshness Policy

Status: first tunable policy, 2026-05-05.

Atlas treats visual context as decaying state, not a permanent fact. A heartbeat capture can be reused for a later user turn only when the physical/session signals say it is still safe enough for the request.

This policy also feeds dynamic heartbeat cadence. Stable, high-confidence context can slow the perception loop; unstable, moving, low-confidence, or high-risk context should speed it up within configured limits. Heartbeat freshness uses wall-clock age from `latestObservationAt`; heartbeat checks that skip capture do not bump freshness.

## Inputs

The first policy uses:

- context availability
- context age
- visual confidence
- stability (`stable`, `transitioning`, `unknown`)
- motion state (`stationary`, `handheld-stable`, `turning`, `walking`, `vehicle`, `unknown`)
- user question use case (`descriptive`, `confirmation`, `navigation`, `high-risk`)
- relevance flag
- refresh latency / analysis latency health

## Default maximum ages

These are starting values, not gospel:

| Motion state | Descriptive | Confirmation | Navigation | High-risk |
| --- | ---: | ---: | ---: | ---: |
| stationary | 120s | 45s | 30s | refresh |
| handheld-stable | 60s | 30s | 15s | refresh |
| turning | 15s | 8s | 5s | refresh |
| walking | 8s | 5s | 3s | refresh |
| vehicle | 5s | 3s | 1s | refresh |
| unknown | 15s | 15s | 10s | refresh |

## Decisions

The policy returns:

- `reuse`: answer can use current context.
- `background-refresh`: current context is usable, but decaying; answer may proceed while a refresh is scheduled soon.
- `degraded-reuse`: context is stale, but the refresh path is degraded; reuse stable context to avoid churn unless the request is high-risk/navigational.
- `refresh`: capture before answering.

Visual refresh health bands:

- `healthy`: refresh path is within normal loop timing.
- `slow`: refresh latency is elevated, but still usable.
- `degraded`: refresh latency is high enough that immediate repeat refreshes can make UX worse.
- `unavailable`: refresh path is too slow/unavailable for normal cadence.

## Examples

- `What am I looking at?` while stationary with a 60s-old stable image: usable, but background refresh soon.
- `Is this the right part?` while walking with a 6s-old image: refresh first.
- `Am I in the right place?` while navigating: short freshness window.
- `Which wire should I cut?`: refresh regardless of age.
- If cloud vision returns after 80s, stable descriptive context may be reused with a degraded-performance hint rather than immediately starting another refresh loop.

## Future motion source

The initial policy can use manually/inferred motion state. Later, the Android phone accelerometer/gyro should feed sensor observations into the same state:

```ts
motionState: 'stationary' | 'handheld-stable' | 'turning' | 'walking' | 'vehicle' | 'unknown'
```

Even rough IMU-derived activity recognition is enough to tune visual decay better than wall-clock age alone.

## Heartbeat weighted freshness

Heartbeat planning starts from a 30s base stale window, then applies simple v0 multipliers before deciding whether to capture:

| Signal | Multiplier |
| --- | ---: |
| high-risk relevance | 0.25x |
| transitioning scene | 0.25x |
| walking/turning | 0.33x |
| vehicle | 0.25x |
| handheld-stable | 0.75x |
| stationary | 1.25x |
| stable + high confidence | 1.5x |
| medium confidence | 0.75x |
| low confidence | 0.5x |
| degraded/unavailable refresh while stable | 2x |

The resulting stale window is clamped between 5s and 120s. Heartbeat decisions include `freshness.contextAgeMs`, `freshness.staleAfterMs`, `freshness.multiplier`, `freshness.stale`, and the signals used, so loop journals can prove that context is aging toward stale rather than being refreshed by check-only ticks.

## Capture budget / device pressure

Heartbeat planning also receives a v0 Atlas-side capture budget derived from recent `observation.captured` events. This is not real phone battery or thermal telemetry yet; it is a protective placeholder until a native device runtime can report actual health.

Budget states:

| Status | Meaning |
| --- | --- |
| `healthy` | low capture pressure |
| `warm` | capture rate or latency is elevated, but still allowed |
| `constrained` | non-urgent stale stable refreshes should be deferred when context exists |
| `cooldown` | heartbeat captures should be deferred for a short cooldown |

The first policy considers captures in the last minute, captures in the last five minutes, and average recent capture latency. High-risk or unstable situations can still override `constrained`; `cooldown` defers heartbeat captures to avoid runaway device pressure.

## Dynamic cadence output

Heartbeat planning now pairs freshness assessment with a cadence decision:

```ts
type HeartbeatCadenceMode =
  | 'idle'
  | 'stable-scene'
  | 'active-task'
  | 'unstable-scene'
  | 'high-risk';
```

The cadence decision explains why the next heartbeat should be slower or faster, so CLI output and session inspection can show whether Atlas is conserving resources, tracking a live task, or reacting to unstable context. The first hook maps idle/non-observable sessions to `idle`, stale/missing-but-otherwise-steady context to `active-task`, fresh high-confidence stable context to `stable-scene`, moving/transitioning/low-confidence context to `unstable-scene`, and explicit high-risk relevance to `high-risk`.
