# Visual Freshness Policy

Status: first tunable policy, 2026-05-05.

Atlas treats visual context as decaying state, not a permanent fact. A heartbeat capture can be reused for a later user turn only when the physical/session signals say it is still safe enough for the request.

This policy should also feed dynamic heartbeat cadence. Stable, high-confidence context can slow the perception loop; unstable, moving, low-confidence, or high-risk context should speed it up within configured limits.

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
