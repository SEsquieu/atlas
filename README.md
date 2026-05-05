# Atlas

Atlas is a provider-agnostic physical agent loop framework.

It binds real-world devices — phones, cameras, microphones, speakers, GPS, and future sensors — to upstream agent runtimes so an agent can operate as a situated, living helper in the physical world.

Atlas is not an OpenClaw-native layer. OpenClaw is the first upstream provider adapter. Android is the first downstream device adapter.

## MVP Stack

```text
Android phone downstream
  → Atlas device adapter
  → Atlas session core
  → Atlas OpenClaw provider adapter
  → OpenClaw upstream runtime
```

## Core Idea

Atlas owns the durable physical session loop:

- device bindings
- heartbeat/perception loop
- user interaction loop
- context freshness/confidence
- observation state
- tool execution
- audit trail

Provider adapters should stay thin. Device adapters should stay swappable.

## Repository Layout

```text
atlas/
  BUILD_DOC.md
  README.md
  docs/
  packages/
    atlas-core/
    atlas-config/
    atlas-device-android/
    atlas-provider-openclaw/
    atlas-test-harness/
  examples/
    android-openclaw-basic/
```

## Start Here

Read [`BUILD_DOC.md`](./BUILD_DOC.md) first. It is the current source of truth for architecture and MVP scope.

Then read [`ROADMAP.md`](./ROADMAP.md) for the implementation sequence.

## Development Workflow

Before committing Atlas changes, run:

```bash
npm run build
npm test
```

Useful harness commands:

```bash
npm --workspace @atlas/test-harness run demo
npm --workspace @atlas/test-harness run demo:android-bridge
npm --workspace @atlas/test-harness run inspect -- demo-session
```

Useful CLI commands after building:

```bash
node packages/atlas-cli/dist/index.js session create demo-session --name "Demo" --goal "Try Atlas"
node packages/atlas-cli/dist/index.js session start demo-session
node packages/atlas-cli/dist/index.js session heartbeat demo-session --config atlas.config.json
node packages/atlas-cli/dist/index.js session ask demo-session --text "What am I looking at?" --config atlas.config.json
node packages/atlas-cli/dist/index.js config inspect
node packages/atlas-cli/dist/index.js sessions list
node packages/atlas-cli/dist/index.js session inspect demo-session
```
