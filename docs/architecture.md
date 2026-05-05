# Atlas Architecture

Atlas is organized around a stable core with swappable upstream provider adapters and downstream device adapters.

```text
Device Adapter → Atlas Core → Provider Adapter → Agent Runtime
```

The core owns the physical session loop. Adapters translate.

## Core Responsibilities

- session lifecycle
- event intake
- heartbeat/perception loop
- user interaction loop
- materialized state
- context freshness and confidence
- tool arbitration/execution
- audit logging

## Adapter Responsibilities

- Device adapters expose normalized physical capabilities.
- Provider adapters map normalized Atlas turns to runtime-native calls.

Adapters should not own session continuity or long-term policy.
