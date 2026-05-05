# Android + OpenClaw Basic Example

First target scenario for Atlas:

```text
Android phone camera
  → Atlas Android device adapter
  → Atlas Core session loop
  → Atlas OpenClaw provider adapter
  → OpenClaw runtime
```

## Initial Scenario

User asks: “What am I looking at?”

Expected behavior:

1. Atlas checks visual context freshness.
2. Atlas captures a fresh Android phone image if needed.
3. Atlas updates perception state.
4. Atlas sends a normalized turn to OpenClaw.
5. OpenClaw responds using current visual context.
6. Atlas records the audit trail and returns the response.
