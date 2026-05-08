# Atlas Latency Notes

Status: first live Android/OpenClaw measurements, 2026-05-05.

## Why this matters

Atlas does not need every physical perception refresh to be user-visible. A capture + analysis round trip can be valuable even when it never becomes speech: it refreshes session state, gives the next user turn fresher context, and lets the significance gate decide whether anything is worth interrupting the user about.

The product target is therefore not only raw latency. The target is *felt* latency:

```text
user asks -> immediate speech acknowledgement -> perception refresh in parallel -> speak only if the result matters
```

## Initial OpenClaw bridge measurement

Live path tested:

```text
Android phone -> OpenClaw Android Camera Bridge -> workspace image staging -> OpenClaw media understanding -> openai-codex/gpt-5.5 vision summary
```

Representative plugin timing from the instrumented bridge:

```text
total=7358ms capture=3861ms stage=15ms analysis=3482ms
```

Interpretation:

- `captureMs` is not just camera sensor time. It includes the bridge helper path, OpenClaw CLI/helper overhead, gateway/node routing, Android capture, file transfer, and stdout/temp-file resolution.
- `stageMs` is effectively free at this scale.
- `analysisMs` is already usable with OpenClaw/Codex when the gateway is healthy.
- A custom Atlas runtime can plausibly reduce capture latency by removing CLI/process startup and keeping node/device connections warm.

## Product implication

A 5-8 second raw perception loop is viable when Atlas decouples perception from spoken response:

- User loop can acknowledge immediately: "Got it — taking a quick look."
- Heartbeat loop can refresh silently and update context.
- Significance gate can suppress speech when the scene is unchanged or unimportant.
- Provider calls can be reserved for user-dependent questions or meaningful scene changes.

The first cheap significance gate compares consecutive observations using summary-token delta, explicit scene-change scores when present, motion/quality changes, confidence drops, and simple actionable/safety cues. It records `none`, `low`, `meaningful`, or `actionable`; only `meaningful`/`actionable` are eligible for provider review, and only `actionable` may pass the future proactive speech hard gate.

## Optimization tracks

### Capture-side optimizations

- Replace bridge CLI spawning with a persistent device/runtime connection.
- Keep Android/node transport warm.
- Lower image size for heartbeat/ambient perception.
- Skip archive/workspace copies for non-audit heartbeat ticks, or make them async.
- Stream bytes directly to analyzers when archival storage is unnecessary.

### Analysis-side optimizations

- Keep upstream vision/runtime warm.
- Start long-lived image workers before expensive OpenClaw model/provider discovery, and report readiness once the local HTTP worker is listening. Otherwise a cold `ensureOpenClawModelsJson` pass can look like the ambient loop hung before tick 1 even though it is only initializing OpenClaw internals.
- Treat the OpenClaw image worker as a persistent workspace service, not a per-loop disposable child. Short ambient runs and one-off asks should discover/reuse the registry worker and leave it alive so Atlas pays OpenClaw initialization and first-describe warmup once whenever possible.
- Use cheaper background analyzers for ambient ticks.
- Run high-quality provider vision only for explicit questions, uncertainty, or significant changes.
- Cache the latest scene summary and ask for deltas where supported.
- Separate analysis artifacts from spoken responses so most perception refreshes are silent.

## Measurement rules

Atlas should record timing fields as part of observation metadata when available:

```json
{
  "totalMs": 7358,
  "captureMs": 3861,
  "stageMs": 15,
  "analysisMs": 3482
}
```

These timings are adapter/runtime telemetry. Atlas Core should store them as observation metadata without becoming OpenClaw-specific.

## Current caution

OpenClaw May 2026 builds (`2026.5.x`) showed Gateway event-loop/CPU saturation on this Windows setup. Rolling back to `2026.4.23` restored fast chat/tool dispatch. Atlas should preserve the ability to swap upstream/provider runtime paths so runtime regressions do not block the physical loop architecture.
