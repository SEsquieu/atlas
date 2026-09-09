# Runtime failure semantics

Atlas treats failure as state, not as an exception string to erase after the next render.

| Situation | Current disposition | Implementation |
| --- | --- | --- |
| Connection fails before acceptance | Endpoint may fail over | `CapabilityRouter.route` / `stream` and `InferenceUnavailableException.outcomeAmbiguous` |
| Provider may have accepted a request | No automatic endpoint retry | Prevents duplicate paid work or effects when outcome is ambiguous. |
| Interaction exceeds 60 seconds | Turn becomes `SOFT_TIMED_OUT`; UI detaches; diagnostic generation continues | `AtlasMobileRuntime.streamModelStep` |
| Detached generation completes | Turn becomes `COMPLETED_LATE`; result and telemetry remain inspectable, not spoken | `provider.completed_late` event |
| Detached generation fails/cancels | Distinct `late_failed` / `late_cancelled` evidence | Late job lifecycle in `AtlasMobileRuntime` |
| User interrupts speech/generation | Generation token changes; speech stops; turn is cancelled | `cancelActiveTurn`, delivery-aware context |
| Tool was running during interruption/restart | Outcome becomes `UNKNOWN`; Core will not retry automatically | `markRunningToolsUnknown`, `executeToolLocked` |
| TTS fails | Text remains durable; delivery becomes failed/text fallback | `SpeechController`, speech segment records |
| Fresh high-risk visual sample is unavailable | Current-world answer fails closed | `startUserTurnLocked` + `FreshnessPolicy` |
| Process restarts | Interrupted turns/tools/speech are reconciled into explicit terminal/unknown states | `AtlasDatabase.recoverInterruptedRuntime` |

These semantics are intentionally conservative. A timeout says the interaction deadline passed; it does not prove that a remote system did nothing. A stronger model cannot repair missing evidence or convert an unknown external effect into a known one.

## Known gaps

- Native restart recovery is implemented but still needs device tests covering actual OS process death during capture, inference, speech, and tool execution.
- Provider error classification relies on transport/HTTP evidence and cannot know every vendor's acceptance semantics.
- The diagnostic late-result path is alpha-facing observability. A later product surface may hide raw detail by default while retaining the evidence for export and debugging.
- Event vocabularies between Kotlin and TypeScript overlap but are not yet validated by one shared schema.
