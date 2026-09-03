# Atlas Android POC

This app runs Atlas Core on an Android phone. It is a reference experience for the complete physical loop, not a thin node controlled by another agent runtime.

## Requirements

- Android Studio with JDK 17
- Android SDK 35
- a physical Android device running Android 9 (API 28) or newer
- an OpenAI-compatible inference endpoint reachable from the phone

Open this directory directly in Android Studio and run the `app` configuration. The repository does not commit machine-specific `local.properties`; Android Studio creates it from your SDK location.

The default APK leaves Atlas Cloud disabled. A gateway deployment can enable account sign-up, subscription checkout, credit-block purchase, balance display, and managed inference by supplying the three Gradle properties documented below. BYOI never requires those values.

## First run

1. Grant camera, microphone, and notification access. Atlas acquires camera, microphone, speech, and motion resources on session start/resume and releases them on pause/end.
2. Open **Inference** and add an endpoint name, base URL, model, and optional API key.
3. Mark the endpoint as image-capable only if its chat-completions API accepts an `image_url` content part.
4. Open **Session**, enter a durable goal, and start. New sessions begin with **Live Context** off.
5. Tap **Observe**, type a question, or tap **Ask** for speech recognition.
6. Enable **Live Context** only when Atlas should maintain rolling physical context in the background.
7. Inspect **Events** to see the ordered session trace.

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

## Provider contract

The POC uses non-streaming OpenAI-compatible chat completions:

- text requests contain system and user messages;
- vision requests include a base64 data URL in an `image_url` content part;
- `Authorization: Bearer …` is omitted when no key is configured;
- `X-Atlas-Request-Id` supports cross-system tracing.
- `X-Atlas-Capability`, `X-Atlas-Risk`, `X-Atlas-Latency-Class`, and `X-Atlas-Media-Purpose` describe the job without naming a vendor model.
- compatible gateways may return `X-Atlas-Model`, `X-Atlas-Profile`, `X-Atlas-Route-Reason`, and `X-Atlas-Route-Revision`; Core records them in the event log.

Routes are expressed as capabilities, not model names. The initial UI adds each configured endpoint to `fast`, `reasoning`, and `fallback`, and adds image-capable endpoints to `vision`. Core tries routes in order and records each attempt.

See [`../../docs/model-routing.md`](../../docs/model-routing.md) for the managed model-selection policy. Direct OpenAI-compatible endpoints may ignore the Atlas headers.

## Known POC limitations

- The UI does not yet reorder routes or assign separate endpoints per capability.
- Chat completions are non-streaming and tool-call responses are not yet supported.
- Speech recognition uses the Android-installed recognition service and may itself be cloud-backed.
- A single latest session is resumed after process restart.
- Session permissions use conservative defaults and are not yet editable or persisted per session.
- Observation retention is unbounded.
- There is no signed release build or Play distribution. Atlas Cloud plumbing exists but is not a production service until deployed, configured, abuse-protected, and operationally monitored.

Those are release tasks, not reasons to couple Core to an inference vendor.
