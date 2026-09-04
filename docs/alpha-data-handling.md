# Closed-alpha data handling specification

Status: engineering specification and disclosure source, not a final legal privacy policy.

## Data classes

| Data | Default location | Leaves device when |
| --- | --- | --- |
| Session/turn state | Android private SQLite | Included in an explicit diagnostic export or future opted-in sync |
| Messages and summaries | Android private SQLite | Sent as bounded inference context or explicitly exported |
| Captured media derivative | Android private app storage | Attached to a vision inference request or explicit diagnostic export |
| Raw camera capture | Temporary private processing | It should be deleted after derivative creation and never uploaded |
| Speech audio | Android speech service boundary | The configured Android recognizer receives it; Atlas stores transcript, not raw audio |
| Provider/API credentials | Android encrypted storage or server secrets | Presented only to the configured endpoint; operator provider keys never enter the APK |
| Operational inference metadata | Atlas Managed service | A managed request occurs |
| Tool arguments/results | Local event/message store | Included in bounded provider context or explicit diagnostic export |

## Inference paths

### BYOI

The user chooses the endpoint and is responsible for that provider's data practices. Atlas should show whether the endpoint is local/LAN or remote and whether a request contains an image.

### Atlas Managed

Atlas Managed receives authentication, request context, optional resized image derivative, tool definitions, and routing metadata required to perform inference. The provider receives the bounded model request. The service retains metering and operational metadata; central retention of content is off by default.

### Platform speech services

Android speech recognition and TTS may be implemented by software or services selected by the device manufacturer/user. Atlas must disclose this boundary and must not describe platform STT as on-device unless verified for the active recognizer.

## Required user controls

- Pause and end the physical session
- Disable Live Context with immediate effect
- Inspect which inference route is active
- Delete a session and its owned media/state
- Export a session in human- and machine-readable form
- Remove BYOI credentials and sign out of Atlas Managed
- Decline diagnostic content upload

## Implemented alpha defaults

- Completed-session image derivatives expire after 7 days by default; the user can select 1, 7, or 30 days.
- Transcripts, memory, and audit records remain until explicit session deletion.
- Session export is an explicit local share action and contains `transcript.txt` plus `session.json`; it never contains provider credentials.
- Session deletion removes messages, speech delivery records, turns, clarification state, tool records, memory, summaries, observations, events, the session row, and Atlas-owned private media.
- Raw camera files remain temporary and are deleted after derivative processing.
- There is no automatic diagnostic content upload or cloud synchronization.

## Diagnostic collection

Operational metrics should exclude content whenever possible. A content-bearing diagnostic package requires a separate explicit action that states:

- which sessions and data classes are included;
- destination and purpose;
- retention period;
- whether the user can review/redact it; and
- how deletion is requested.

## Release verification

Before each alpha build, verify this specification against actual Android, managed gateway, Supabase, provider, crash-reporting, and distribution behavior. Product copy must describe implementation, not aspiration.
