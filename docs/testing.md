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
   - Expected: state updates silently; no babbling; no unnecessary provider calls.

5. **Adapter independence**
   - Run the same normalized turn through the OpenClaw adapter and a fake adapter.
   - Expected: Atlas Core behavior remains unchanged.
