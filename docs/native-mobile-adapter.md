# Native Mobile Adapter Contract

Status: draft target contract for a future Atlas-owned mobile adapter. The current OpenClaw Android path remains a proof adapter for validating loop semantics, telemetry, and user feel; it should not dictate Atlas Core behavior.

## Purpose

Atlas wants a phone adapter that can keep physical context warm without making the user wait on every explicit question. The adapter should expose predictable perception, lifecycle, sensor, and permission signals while leaving loop policy to Atlas Core.

Core principle: the mobile adapter samples and reports the world; Atlas decides when to sample, when to reuse, when to ask, and when to speak.

## Required MVP Capabilities

### Visual capture

The MVP adapter must provide a snapshot API equivalent to `camera.capture`:

```ts
type CaptureImageRequest = {
  reason: string;
  quality?: 'low' | 'medium' | 'high';
  maxWidth?: number;
  timeoutMs?: number;
  freshnessDeadlineMs?: number;
};

type CaptureImageResult = {
  observation: Observation;
};
```

The returned `Observation` must include:

- `id`
- `type: 'image'`
- `capturedAt`
- `deviceId`
- `mediaRef` or an equivalent readable media handle
- `telemetry.observedAt`
- `telemetry.availableAt`
- `telemetry.latencyMs.total`
- `telemetry.latencyMs.capture`
- optional `telemetry.latencyMs.stage`
- optional `telemetry.latencyMs.analysis`
- optional quality hints: confidence, blur, low-light, occlusion, motion

Atlas freshness ages from `observedAt`, not from `availableAt`. Slow capture or analysis means context arrives older; the adapter must not hide that.

### Lifecycle/status

The adapter should report status separately from capture results:

```ts
type MobileAdapterStatus = {
  reachable: boolean;
  foregroundState: 'foreground' | 'background' | 'locked' | 'unknown';
  cameraState: 'cold' | 'warming' | 'warm' | 'capturing' | 'blocked' | 'unknown';
  permissionState: Record<string, 'granted' | 'denied' | 'prompt-required' | 'unknown'>;
  battery?: { level?: number; charging?: boolean; saverMode?: boolean };
  thermal?: 'nominal' | 'warm' | 'hot' | 'throttled' | 'unknown';
  network?: { type?: 'wifi' | 'cellular' | 'offline' | 'unknown'; quality?: 'good' | 'poor' | 'unknown' };
};
```

Atlas can use this to choose graceful reuse, defer refresh, or surface a user-facing caveat.

### Errors

Errors should be structured and non-ambiguous:

```ts
type MobileAdapterErrorCode =
  | 'DEVICE_UNREACHABLE'
  | 'APP_BACKGROUND_UNAVAILABLE'
  | 'CAMERA_PERMISSION_DENIED'
  | 'CAMERA_BUSY'
  | 'CAPTURE_TIMEOUT'
  | 'LOW_BATTERY_DEFERRED'
  | 'THERMAL_THROTTLED'
  | 'NETWORK_UNAVAILABLE'
  | 'UNKNOWN';
```

Errors should include:

- code
- human-readable message
- retryable boolean
- suggested retry delay if known
- latest status snapshot if available

Atlas should be able to distinguish “try again soon,” “reuse stale context with caveat,” and “ask the user to intervene.”

## Warm Camera Lifecycle

A native adapter should support optional camera warming, because the user-loop goal is felt latency, not raw benchmark heroics.

Desired lifecycle:

1. `prepareCamera({ reason, maxDurationMs })`
2. adapter opens/wakes preview path when allowed
3. `captureImage(...)` can sample from warm state quickly
4. `releaseCamera({ reason })` when Atlas cadence relaxes or battery/thermal pressure rises

Warm state is advisory. Atlas must work correctly if the adapter cannot keep the camera warm.

## Sensor Streams

Sensor data is sugar on the loop, not the loop itself. The adapter should expose these as optional observations or status streams:

- accelerometer/gyro motion classification
- orientation changes
- GPS / fused location
- Wi-Fi / Bluetooth environment hints
- battery and thermal state
- screen/lock/foreground state
- optional audio/VAD state later

These signals should tighten or relax cadence and freshness windows. They should not replace visual heartbeat semantics.

## Timing Telemetry

Every adapter operation should report timing with the same split Atlas already records:

```ts
type AdapterTiming = {
  totalMs?: number;
  captureMs?: number;
  stageMs?: number;
  analysisMs?: number;
  transportMs?: number;
  queueMs?: number;
};
```

The goal is adapter comparison, not adapter-specific policy. A future native adapter should be measurable against the OpenClaw proof adapter without changing Atlas Core.

## Permission Model

The adapter must never silently expand permissions. It should surface whether each capability is available, unavailable, or awaiting human approval.

Atlas Core remains the policy layer for:

- when capture is allowed
- whether proactive observation is permitted
- whether location/microphone can be sampled
- when user notification is required

## Non-goals for MVP

Do not block the native adapter MVP on:

- continuous video streaming
- full AR navigation overlays
- always-on microphone
- background capture on platforms that fight it
- perfect low-power scheduling
- cloud sync or marketplace behavior

The first win is a reliable native perception adapter that makes warm context feel good.

## Acceptance Tests

A native mobile adapter is ready to replace the proof bridge when it can reliably demonstrate:

1. cold snapshot with structured timing
2. warm snapshot with lower capture latency
3. status reporting while foregrounded/backgrounded/locked
4. clear permission-denied and unavailable errors
5. stale-context fallback compatibility in Atlas Core
6. sensor hints influencing cadence/freshness without hardcoding adapter quirks
7. no-camera fake harness parity for Core behavior tests
