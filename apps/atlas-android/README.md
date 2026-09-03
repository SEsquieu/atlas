# Atlas Android POC

This app runs Atlas Core on an Android phone. It is a reference experience for the complete physical loop, not a thin node controlled by another agent runtime.

## Requirements

- Android Studio with JDK 17
- Android SDK 35
- a physical Android device running Android 9 (API 28) or newer
- an OpenAI-compatible inference endpoint reachable from the phone

Open this directory directly in Android Studio and run the `app` configuration. The repository does not commit machine-specific `local.properties`; Android Studio creates it from your SDK location.

## First run

1. Grant camera, microphone, and notification access. Atlas only binds camera and microphone while its foreground session service is running.
2. Open **Inference** and add an endpoint name, base URL, model, and optional API key.
3. Mark the endpoint as image-capable only if its chat-completions API accepts an `image_url` content part.
4. Open **Session**, enter a durable goal, and start.
5. Tap **Observe**, type a question, or tap **Ask** for speech recognition.
6. Inspect **Events** to see the ordered session trace.

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
- Android backups are disabled.
- Cleartext HTTP is enabled for local-network POC endpoints. A public release should replace the global allowance with an explicit network-security policy and visible per-endpoint warning.
- Removing the app removes its local data. There is no cloud sync in this POC.

This is not yet production privacy UX. Before external beta, Atlas needs retention controls, session deletion/export, redaction options, a privacy disclosure, and security review.

## Provider contract

The POC uses non-streaming OpenAI-compatible chat completions:

- text requests contain system and user messages;
- vision requests include a base64 data URL in an `image_url` content part;
- `Authorization: Bearer …` is omitted when no key is configured;
- `X-Atlas-Request-Id` supports cross-system tracing.

Routes are expressed as capabilities, not model names. The initial UI adds each configured endpoint to `fast`, `reasoning`, and `fallback`, and adds image-capable endpoints to `vision`. Core tries routes in order and records each attempt.

## Known POC limitations

- The UI does not yet reorder routes or assign separate endpoints per capability.
- Chat completions are non-streaming and tool-call responses are not yet supported.
- Speech recognition uses the Android-installed recognition service and may itself be cloud-backed.
- A single latest session is resumed after process restart.
- Session permissions use conservative defaults and are not yet editable or persisted per session.
- Observation retention is unbounded.
- There is no signed release build, CI Android build, Play distribution, account, subscription, or managed inference endpoint.

Those are release tasks, not reasons to couple Core to an inference vendor.
