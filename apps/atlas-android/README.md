# Atlas Android POC

This app runs Atlas Core on an Android phone. It is a reference experience for the complete physical loop, not a thin node controlled by another agent runtime.

## Requirements

- Android Studio with JDK 17
- Android SDK 35
- a physical Android device running Android 9 (API 28) or newer
- an OpenAI-compatible inference endpoint reachable from the phone

Open this directory directly in Android Studio and run the `app` configuration. The repository does not commit machine-specific `local.properties`; Android Studio creates it from your SDK location.

The default APK leaves Atlas Cloud disabled. A gateway deployment can enable account sign-up, subscription checkout, credit-block purchase, balance display, and managed inference by supplying the three Gradle properties documented below. BYOI never requires those values.

Android database version 7 creates a personal workspace automatically and scopes every new session, observation, event, and memory record to it. The same persistence boundary supports optional site, station, actor, task-run, policy, and scoped-memory identities without placing enterprise administration in the consumer UI.

## First run

1. Grant camera, microphone, and notification access. Atlas acquires camera, microphone, speech, and motion resources on session start/resume and releases them on pause/end.
2. Open **Inference** and add an endpoint name, base URL, model, and optional API key.
3. Mark the endpoint as image-capable only if its chat-completions API accepts an `image_url` content part.
4. Mark it tool-capable only if it accepts OpenAI-compatible function tools and returns assistant `tool_calls`. Enable **Stream** only for endpoints that return chat-completion SSE deltas.
5. Open **Session**, enter a durable goal, and start. New sessions begin with **Live Context** off.
6. Tap **Observe**, type a question, or tap **Talk**. Begin speaking after the haptic and soft chirp when the card says **Speak now**.
7. Enable **Live Context** only when Atlas should maintain rolling physical context in the background.
8. Inspect **Events** to see the ordered session trace.

Examples of base URLs:

| Runtime | Example | Notes |
| --- | --- | --- |
| LAN server | `http://192.168.1.40:11434` | Phone and server must be mutually reachable. |
| Android emulator host | `http://10.0.2.2:11434` | Emulator only. |
| Cloud provider | `https://provider.example.com/v1` | The API key is encrypted with Android Keystore. |

Atlas appends `/v1/chat/completions` unless the configured URL already ends in `/v1` or `/chat/completions`.

## Security and privacy posture

- Provider keys are non-exportable AES-GCM ciphertext backed by Android Keystore.
- Keys are sent only to the endpoint configured by the user.
- Captured frames, transcripts, model responses, and audit events are stored in private app storage.
- Full-resolution camera output is temporary. Atlas normalizes it into a private, opaque media artifact before persistence or inference.
- Android backups are disabled.
- Cleartext HTTP is enabled for local-network POC endpoints. A public release should replace the global allowance with an explicit network-security policy and visible per-endpoint warning.
- Removing the app removes its local data. There is no cloud sync in this POC.

## Media boundary and image budgets

Providers never receive a filesystem path. `MediaRepository` owns private storage under the app's `filesDir/media/v1`, and Core passes providers an immutable in-memory payload plus media ID, MIME type, dimensions, byte count, and SHA-256 digest.

| Purpose | Longest edge | Starting JPEG quality | Hard byte budget |
| --- | ---: | ---: | ---: |
| Heartbeat | 640 px | 70 | 150 KB |
| Standard vision | 1024 px | 78 | 350 KB |
| Detail/high-risk vision | 1600 px | 82 | 750 KB |

Capture performs sampled decoding, EXIF rotation, proportional resize, and iterative recompression/downscaling. It atomically commits only a derivative within budget and deletes the raw temporary file even when processing fails. The observation/event record retains raw and final byte counts and processing latency for measurement.

Freshness and suitability are separate checks. A recent heartbeat thumbnail may satisfy an ordinary scene question, but detail work such as reading a label forces a detail-quality capture even when the thumbnail is new.

## Live Context

Live Context is a persisted Atlas Core session mode, not a provider feature.

- Off is the default. Atlas makes no background captures or inference calls; asks, voice requests, and manual Observe remain available.
- On starts the motion-aware heartbeat immediately and continues through the foreground session service.
- Local scene difference gates semantic review. Background inference has a one-minute cooldown and a hard ceiling of 12 attempts per rolling hour, reconstructed from the durable event log after restart.
- A successful heartbeat interpretation is stored on its source observation and supplied as context to later requests while that observation remains current.
- Pausing or ending the session stops Live Context immediately. Resuming a session whose persisted mode is Live restarts it.

The notification and Session screen visibly distinguish Live Context from a manual physical session. These limits constrain inference calls, not camera captures; capture cadence continues to adapt to motion, battery, and thermal state.

## Optional Atlas Cloud build

After deploying `apps/atlas-cloud`, build a managed-enabled APK with:

```bash
./gradlew assembleDebug \
  -PATLAS_GATEWAY_URL=https://your-gateway.example \
  -PSUPABASE_URL=https://PROJECT.supabase.co \
  -PSUPABASE_PUBLISHABLE_KEY=sb_publishable_REPLACE_ME
```

The app authenticates directly with Supabase, stores the session tokens with Android Keystore, and presents the short-lived access token to Atlas Cloud. OpenAI and Supabase service-role credentials remain on the server. Signing out removes only the managed endpoint from capability routes; direct endpoints are untouched.

This is not yet production privacy UX. Before external beta, Atlas needs retention controls, session deletion/export, redaction options, a privacy disclosure, and security review.

## Speech loop

Speech is the default response surface for active sessions. Push-to-talk uses the Android-installed recognition service; its readiness callback—not the button tap—drives the haptic, chirp, and **Speak now** indicator. Partial transcripts remain UI state until recognition completes.

Atlas streams provider text into Android TTS one complete sentence at a time. Markdown is rendered into speakable text, while the exact provider response remains in the transcript. Tap **Interrupt and talk** while Atlas is speaking to stop playback, cancel remaining generation, and immediately begin another turn. Atlas records completed sentences separately so the next model is never told that an interrupted remainder was heard.

Ordinary spoken answers are intentionally brief. Explicit requests for an explanation receive a larger response contract; physical guidance and safety-sensitive answers lead with the action. TTS failure never removes the text response.

## Provider contract

The app uses OpenAI-compatible chat completions and function tools:

- text requests contain the bounded Atlas-owned conversation, not a provider-owned thread;
- vision requests include a base64 data URL in an `image_url` content part;
- tool-capable endpoints receive JSON-schema function definitions and must return standard assistant `tool_calls`;
- Atlas records, authorizes, executes, and returns `role: tool` results in subsequent model steps;
- `Authorization: Bearer …` is omitted when no key is configured;
- `X-Atlas-Request-Id` supports cross-system tracing.
- UUID-backed managed sessions forward organization scope plus session/task-run correlation for authorization, metering, and audit attribution.
- `X-Atlas-Capability`, `X-Atlas-Risk`, `X-Atlas-Latency-Class`, and `X-Atlas-Media-Purpose` describe the job without naming a vendor model.
- compatible gateways may return `X-Atlas-Model`, `X-Atlas-Profile`, `X-Atlas-Route-Reason`, and `X-Atlas-Route-Revision`; Core records them in the event log.
- streaming endpoints return standard `data: {...}` SSE chat-completion chunks followed by `data: [DONE]`; text and split tool-call arguments are normalized before they reach Core.
- non-streaming endpoints use the same internal stream contract and remain fully supported, but cannot start speech before the complete response arrives.

Routes are expressed as capabilities, not model names. The initial UI adds each configured endpoint to `fast`, `reasoning`, and `fallback`, and adds image-capable endpoints to `vision`. Tool-bearing steps only use endpoints that explicitly advertise support. Core retries another route only when the prior attempt is known not to have been accepted; ambiguous outcomes stop to avoid duplicate cost.

Existing endpoint cards expose **Vision**, **Tools**, and **Stream** capability toggles. Enable only the behavior that endpoint actually implements; Atlas will continue to use a text-only/non-streaming endpoint for conversation but will never send it unsupported modalities.

See [`../../docs/agent-runtime.md`](../../docs/agent-runtime.md) for turn persistence, context assembly, memory admission, tools, recovery, and loop budgets.

See [`../../docs/model-routing.md`](../../docs/model-routing.md) for the managed model-selection policy. Direct OpenAI-compatible endpoints may ignore the Atlas headers.

## Known POC limitations

- The UI does not yet reorder routes or assign separate endpoints per capability.
- Speech recognition uses the Android-installed recognition service and may itself be cloud-backed.
- Speech uses platform TTS voices and does not yet expose voice, rate, cue, or language controls.
- Audio focus, Bluetooth-route validation, and noisy-environment tuning require physical-device alpha testing.
- A single latest session is resumed after process restart.
- Session permissions use conservative persisted defaults but are not yet editable in the UI.
- Observation retention is unbounded.
- There is no signed release build or Play distribution. Atlas Cloud plumbing exists but is not a production service until deployed, configured, abuse-protected, and operationally monitored.

Those are release tasks, not reasons to couple Core to an inference vendor.
