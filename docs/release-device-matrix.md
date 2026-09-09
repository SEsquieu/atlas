# Android Release Acceptance Matrix

Release candidate: `v0.1.0-alpha.5`

Primary device: Galaxy S22 Ultra

Status: record results against the exact signed release commit and APK checksum; CI debug builds do not close this matrix.

## Build identity

| Field | Evidence |
| --- | --- |
| Source commit | Pending frozen `main` SHA |
| Version | `0.1.0-alpha.5` / version code 7 |
| Secure APK SHA-256 | Pending signed workflow |
| LAN APK SHA-256 | Pending signed workflow |
| Signing certificate fingerprint | Pending signed workflow |
| Android/device build | Pending test run |

## Required acceptance tests

Record `pass`, `fail`, or `not applicable`, plus the exported session/event evidence where useful. Never commit a real session export.

| ID | Test | Expected result | Result |
| --- | --- | --- | --- |
| A01 | Fresh install, permissions accepted | First-run disclosure completes; Atlas reaches sparse idle state | Pending |
| A02 | Permission denial/retry | Camera or microphone denial is explained and recoverable without corrupting session state | Pending |
| A03 | On-device llama.cpp text endpoint | Connection test and a short multi-turn Qwen session complete with reasoning, vision, tools, and streaming disabled | Pending |
| A04 | Secure HTTPS endpoint | Secure flavor connects; provider identity/routing state is visible | Pending |
| A05 | Private HTTP endpoint | Secure flavor rejects it; LAN flavor accepts only loopback/private-network addressing with warning | Pending |
| A06 | Push-to-talk and interruption | Listening readiness cue precedes speech; barge-in stops output and does not record unheard text as delivered | Pending |
| A07 | Bluetooth earbuds | STT/TTS use remains understandable; route loss produces a recoverable text surface | Pending |
| A08 | Camera/manual observation | Captured derivative stays within media budget and the source observation/provenance appears in logs | Pending |
| A09 | Vision-disabled endpoint | Atlas does not send image content and reports the capability boundary clearly | Pending |
| A10 | Live Context lifecycle | Start/resume activates it; pause/end stops capture and rejects stale late work | Pending |
| A11 | Timeout and late arrival | Soft timeout is visible; late output is retained diagnostically but never inserted as an accepted response | Pending |
| A12 | Process death/restart | Latest session, clarification, memory, route configuration, and unknown tool state recover conservatively | Pending |
| A13 | Streaming on/off | Compatible streaming endpoint renders clean text without a leading `null`; non-streaming remains functional | Pending |
| A14 | Session export | ZIP contains readable transcript and machine state without provider credentials | Pending |
| A15 | Session deletion/retention | Delete removes session-owned state/media; retention selection survives restart | Pending |
| A16 | Upgrade over prior alpha | Database migration preserves the latest session and endpoint configuration | Pending |

## Release decision

Do not publish the signed APK if A01, A03, A06, A08, A10, A11, A12, A14, or A15 fails. Document non-blocking device-specific limitations in the release notes before publication.
