# Atlas Interface Shape

Atlas is a physical-session runtime with multiple front doors, not just a CLI or just a daemon.

## Layers

```text
@atlas/core
  library/runtime engine

@atlas/cli
  control/debug/admin surface

@atlas/config
  config schema and loader for sessions/store/runtime defaults

atlas daemon / runner
  future long-lived background loop process

atlas.config.*
  repo/session config consumed by CLI now and daemon later

hooks/events
  future integration surface for apps/runtimes
```

## Current CLI

The first CLI package is `@atlas/cli`.

After building:

```bash
node packages/atlas-cli/dist/index.js session create <sessionId> [--config atlas.config.json]
node packages/atlas-cli/dist/index.js session start <sessionId>
node packages/atlas-cli/dist/index.js session pause <sessionId>
node packages/atlas-cli/dist/index.js session resume <sessionId>
node packages/atlas-cli/dist/index.js session end <sessionId>
node packages/atlas-cli/dist/index.js session heartbeat <sessionId> [--config atlas.config.json]
node packages/atlas-cli/dist/index.js session ask <sessionId> --text "What am I looking at?" [--config atlas.config.json]
node packages/atlas-cli/dist/index.js config inspect
node packages/atlas-cli/dist/index.js sessions list
node packages/atlas-cli/dist/index.js session inspect <sessionId>
```

`session inspect` includes timing when available:

- latest user turn duration
- Atlas capture round trip
- Android bridge total/capture/stage/analysis timings
- provider round trip

The Android/OpenClaw example also provides a one-command live demo:

```bash
npm run demo:live-android -- "What am I looking at?"
```

The CLI can now resolve command-backed edge adapters from config:

- `@atlas/device-android/bridge-command`
- `@atlas/provider-openclaw/command`

These let live wrappers connect Atlas to OpenClaw/Android without importing OpenClaw-specific tool APIs into Atlas Core. See `docs/adapter-contracts.md` for the command environment contracts.

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

The CLI is not the living-agent product by itself. It is the control surface used to create, inspect, debug, script, and eventually manage the background runner.

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
