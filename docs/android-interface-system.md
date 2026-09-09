# Atlas Android interface system

## Product posture

Atlas is a persistent physical-agent runtime whose body is an Android phone. The primary surface is not a transcript or a camera. It is a calm projection of what Core currently knows, what it is doing, and how the user can interact with it.

The interface must never fabricate activity to fill space. Surfaces appear only when backed by durable session or runtime state.

## Navigation

| Destination | Responsibility |
| --- | --- |
| Atlas | Live presence, active context, observations, permissions, voice, and primary interaction |
| Session | Durable conversation, memory, observations, tool history, late results, and session lifecycle |
| System | Inference routes, endpoints, managed inference, retention, export, deletion, health, and raw events |

## Semantic color

- Electric green means live, selected, observed, listening, speaking, or successfully established.
- White and neutral gray carry ordinary information.
- Amber means attention, uncertainty, staleness, degraded operation, or requested permission.
- Red is reserved for failure and destructive action.
- Green is not ambient decoration.

## Runtime projection

The UI reads `RuntimeSnapshot`; it does not infer a second lifecycle. Listening state takes precedence over broad runtime phase so the user can always tell whether the microphone is preparing, ready, hearing, or processing. Camera capture, inference, speech, degraded routing, errors, and pause state use distinct language.

Soft timeout exits the interactive wait while preserving late output as diagnostic evidence. A late completion is never silently promoted into accepted conversation or spoken automatically.

## Evidence surfaces

Observation UI may contain only facts supported by current runtime state:

- camera summary and its exact source observation;
- capture and interpretation age;
- motion and view stability;
- confidence when supplied;
- device state;
- user-confirmed clarification;
- tool state.

Task/checklist UI must be backed by a structured task or procedure. A session goal or model prose must not be presented as completed procedural state.

## Voice

Push-to-talk is the stable one-handed anchor. Visual treatment follows real microphone and speech state. The main control supports barge-in while Atlas is speaking. Observe and text fallback remain adjacent. The interface must not imply continuous or passive listening.

## Camera

Atlas currently owns bounded still-image observations, not a continuous camera feed. The UI presents camera material as evidence Atlas captured at a known time. Camera appears when useful and never becomes a stock camera-shaped home screen.

## Regression boundary

Visual work may reorganize access but must preserve session lifecycle, Live Context, PTT, text fallback, Observe, streaming, barge-in, clarifications, tool authorization, memory controls, late-result diagnostics, provider capabilities, managed inference, export, deletion, retention, health, and the event stream.
