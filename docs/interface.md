# Atlas Interface Shape

Atlas is a physical-session runtime with multiple front doors, not just a CLI or just a daemon.

## Layers

```text
@atlas/core
  library/runtime engine

@atlas/cli
  control/debug/admin surface

atlas daemon / runner
  future long-lived background loop process

atlas.config.*
  future repo/session config

hooks/events
  future integration surface for apps/runtimes
```

## Current CLI

The first CLI package is `@atlas/cli`.

After building:

```bash
node packages/atlas-cli/dist/index.js sessions list
node packages/atlas-cli/dist/index.js session inspect <sessionId>
```

By default, the CLI reads sessions from:

```text
.atlas-cache/sessions
```

Override with either:

```bash
node packages/atlas-cli/dist/index.js sessions list --store <path>
ATLAS_STORE=<path> node packages/atlas-cli/dist/index.js sessions list
```

## Design Intent

The CLI is not the living-agent product by itself. It is the control surface used to inspect, debug, script, and eventually manage the background runner.

The long-term interactive shape is expected to be:

```text
atlas run
  loads config
  starts sessions
  runs heartbeat loops
  receives user events
  dispatches provider/device/analyzer adapters
  writes event logs/materialized state
```
