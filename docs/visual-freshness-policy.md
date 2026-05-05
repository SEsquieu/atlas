# Visual Freshness Policy

Status: first tunable policy, 2026-05-05.

Atlas treats visual context as decaying state, not a permanent fact. A heartbeat capture can be reused for a later user turn only when the physical/session signals say it is still safe enough for the request.

## Inputs

The first policy uses:

- context availability
- context age
- visual confidence
- stability (`stable`, `transitioning`, `unknown`)
- motion state (`stationary`, `handheld-stable`, `turning`, `walking`, `vehicle`, `unknown`)
- user question use case (`descriptive`, `confirmation`, `navigation`, `high-risk`)
- relevance flag

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
- `refresh`: capture before answering.

## Examples

- `What am I looking at?` while stationary with a 60s-old stable image: usable, but background refresh soon.
- `Is this the right part?` while walking with a 6s-old image: refresh first.
- `Am I in the right place?` while navigating: short freshness window.
- `Which wire should I cut?`: refresh regardless of age.

## Future motion source

The initial policy can use manually/inferred motion state. Later, the Android phone accelerometer/gyro should feed sensor observations into the same state:

```ts
motionState: 'stationary' | 'handheld-stable' | 'turning' | 'walking' | 'vehicle' | 'unknown'
```

Even rough IMU-derived activity recognition is enough to tune visual decay better than wall-clock age alone.
