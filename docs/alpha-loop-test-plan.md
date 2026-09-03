# Atlas closed-alpha loop test plan

Use this plan on physical Android hardware before calling the runtime alpha-ready. Run once against a direct LAN endpoint and once against Atlas Cloud when the gateway is deployed. Mark the endpoint's **Tools** capability only when it implements OpenAI-compatible function calling.

## Record for every run

- Phone model, Android version, Atlas version, endpoint, and configured model
- Whether Live Context was enabled
- Time from send/end-of-speech to first audible response
- Capture, media-processing, provider, and total turn latency from the event view
- Input/output usage reported by the gateway when available
- Any incorrect claim about a tool, observation, remembered fact, or current state

Do not treat a fluent answer as a pass when the event trace shows stale evidence, a missing tool result, or an unapproved effect.

## Acceptance scenarios

| Scenario | Procedure | Required result |
| --- | --- | --- |
| Text continuity | Establish two named objects, correct Atlas once, then refer to each indirectly over six turns | The correction and references survive; the transcript is visibly durable |
| Voice continuity | Complete the same task through speech, interrupt one spoken response, then continue | Interruption is local and the next turn retains prior dialogue |
| Speak-ready cue | Tap Talk while looking away from the screen | The haptic and subtle chirp occur only when recognition is ready; the card simultaneously says **Speak now** |
| Partial transcript | Speak a multi-clause request at a natural pace | Partial text updates without creating durable user messages; one final transcript starts one turn |
| Sentence streaming | Use an SSE endpoint with a response of at least three sentences | TTS starts after the first complete sentence, before generation completes; the event log records each segment |
| Barge-in delivery truth | Interrupt during sentence two, immediately ask “what did you already tell me?” | Sentence one is recorded delivered; sentence two and queued text are not assumed heard by the next model |
| Non-stream compatibility | Disable Stream for the same endpoint and repeat | The answer remains correct and spoken, but starts after the full response; tool behavior is unchanged |
| Speech failure fallback | Disable or break the selected TTS engine after inference begins | The textual answer remains visible and delivery is marked failed rather than silently completed |
| Fresh vision | Ask what is visible, change rooms, then ask a present-tense question | Atlas captures according to freshness policy and cites no superseded scene as current |
| Detail vision | Show small text after a heartbeat thumbnail exists and ask Atlas to read it | Atlas obtains a detail-budget capture instead of reusing the thumbnail |
| Model-requested refresh | Ask a question whose answer requires a closer/current view | The model proposes `capture_current_view`; Core records, executes, and returns its result before the final answer |
| Device state tool | Ask about battery, charging, network, and motion | Values come from `get_device_state`; no value is invented before the tool result |
| Task memory | Give a constraint needed much later in the session | `atlas_remember` admits visible task/working memory and the later turn uses it |
| Physical memory expiry | Ask Atlas to retain a scene fact, then wait beyond its TTL or move contexts | Environment memory carries observation provenance and is not treated as timeless truth |
| Durable consent | Ask Atlas to remember a preference for future sessions | The turn pauses for approval; declining records a tool result, approving exposes the memory in a later local session |
| Tool denial | Decline a proposed durable memory | The model receives the denial and continues without claiming the write succeeded |
| Process death during inference | Send a request, force-stop the app before completion, and reopen it | The turn is `INTERRUPTED`; Atlas does not silently resubmit it |
| Process death during effect | Use a test external adapter, terminate during execution, and reopen | The call becomes `UNKNOWN` and is never automatically replayed |
| Turn cancellation | Cancel during provider inference and while awaiting confirmation | HTTP work stops where possible; the durable turn becomes `CANCELLED` |
| Known-safe failover | Make the first route refuse connection and leave a second route healthy | Atlas tries the second declared route and records both attempts |
| Ambiguous failure | Make the first route accept a request and then time out | Atlas stops without trying another paid/effectful route |
| Live Context budget | Enable Live Context in a changing scene for more than one cooldown interval | Local capture continues adaptively; semantic inference obeys cooldown/hourly ceilings |
| Manual mode | Disable Live Context and leave the session active | No background capture or inference occurs; Ask and Observe still work |
| Restart continuity | Complete several turns, background/reopen the app, and continue | Session, dialogue, admitted memory, observation metadata, and pending approvals are restored |

## Release gates

A closed alpha candidate should have:

1. No reproducible false claim that a tool ran or that stale physical context is current.
2. No automatic retry after an ambiguous provider or external-tool outcome.
3. A complete event trail for every sampled turn: creation, context assembly, provider attempt, tool transitions, and disposition.
4. P50/P95 latency and inference-cost measurements split by text, reused vision, refreshed vision, and multi-step tool turns.
5. Successful upgrade of an existing POC install without losing its sessions, observations, or provider secrets.
6. A documented result for at least three phone models and two Android major versions.
7. No duplicate paid fallback after a stream has emitted text or any portion of a tool call.

Before an external beta, add automated SQLite migration/recovery tests, instrumentation coverage on a device farm, retention/delete/export controls, signed builds, privacy disclosures, and an operational test of the managed credit ledger.
