# Atlas Testing Strategy

Initial testing target:

- upstream: OpenClaw
- downstream: Android phone

## MVP Test Scenarios

1. **Fresh context required**
   - Ask: “What am I looking at?”
   - Expected: Atlas captures a fresh image if visual context is stale, missing, or unstable.

2. **Fresh context reused**
   - Ask a visual question shortly after a stable capture.
   - Expected: Atlas may reuse current context if policy allows.

3. **Transitional context rejected**
   - Mark visual context as unstable or blurry.
   - Ask: “Am I in the right place?”
   - Expected: Atlas refreshes before answering.

4. **Heartbeat silence**
   - Run heartbeat ticks in an unchanged scene.
   - Expected: state updates silently; significance is `none` or `low`; no babbling; no unnecessary provider calls.
   - Fake-safe journal command: `npm run demo:ambient-loop -- --ticks 3` writes `ambient-loop.jsonl` and `ambient-loop.md` under the session store.
   - `npm run loop:android -- summary --store <store> --session ambient-loop-fake` parses the JSONL without touching the phone and prints the freshness scorecard.
   - The journal should show `freshness.age` increasing relative to `freshness.staleAfter`; check-only ticks must not reset freshness.

5. **Meaningful ambient change**
   - Run a heartbeat after the scene meaningfully changes.
   - Expected: Atlas records `perception.significance` as `meaningful` and marks the observation eligible for provider review without proactive speech.

6. **Actionable ambient change**
   - Run a heartbeat that detects a safety/action cue.
   - Expected: Atlas records `perception.significance` as `actionable`; this is the only v0 level that may pass the future proactive notification hard gate.

7. **Capture budget / device pressure**
   - Seed recent capture events or run repeated heartbeats.
   - Expected: heartbeat decisions include `captureBudget`; stale stable context can be deferred when budget is `constrained` or `cooldown`.

8. **Adapter independence**
   - Run the same normalized turn through the OpenClaw adapter and a fake adapter.
   - Expected: Atlas Core behavior remains unchanged.
