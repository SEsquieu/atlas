# Closed alpha operating plan

Status: release gate for the first invited Android testers.

## Alpha promise

The alpha validates one experience: a user can carry or place an Android phone, speak naturally to a persistent Atlas session, obtain context-appropriate short spoken responses, interrupt safely, use current physical context, and inspect what happened.

It is not a promise of autonomous operation, emergency guidance, a production billing service, or enterprise administration.

## Cohort and distribution

- Begin with 5 internal/friendly testers, then expand to 10–20 only after one week without a severity-one defect.
- Distribute a signed, versioned APK through a controlled channel. Do not distribute CI debug artifacts as the alpha release.
- Keep a deterministic build record: source commit, version name/code, signing identity fingerprint, build date, configuration profile, and checksum.
- Maintain rollback access to the prior known-good APK.
- Do not make the GitHub repository public merely to distribute the alpha.

## Required onboarding

Before the first session, users must see:

- what camera, microphone, notification, and speech permissions do;
- that Android speech recognition or TTS may use platform/cloud services;
- whether configured inference is BYOI or Atlas Managed;
- when images may leave the phone;
- that Live Context performs background capture/review only while visibly enabled;
- that Atlas can be wrong and is not emergency or professional safety authority;
- how to pause, end, interrupt, delete, and report a session; and
- the sponsored inference limit and what happens at exhaustion.

Consent for product telemetry and consent for uploading session content must be separate. Alpha access must not require unrestricted transcript/image collection.

## Release gates

### Runtime

- Multi-turn context survives process restart.
- Stale and unsuitable visual context forces refresh or an explicit refusal.
- Tool calls pass schema validation, policy, confirmation, idempotency, and step/wall-time limits.
- Barge-in cancels generation and speech without claiming unheard text was delivered.
- Pause/end stops Live Context and rejects late provider results.
- Provider failures preserve the session and produce an understandable recovery path.
- Budget exhaustion stops before the external call.

### Privacy and control

- Session deletion removes local messages, memory, observations, events, and owned media.
- Export produces a user-readable transcript and machine-readable event/state bundle.
- Retention behavior is documented and enforced.
- No production secret is present in the APK, repository, CI logs, or downloadable artifacts.
- Network security does not globally permit cleartext in an alpha release build.
- User can distinguish local/BYOI processing from Atlas Managed processing.

### Quality

- Physical-device matrix covers at least the Galaxy S22 Ultra plus two additional Android models or OS variants.
- Camera, permission denial, Bluetooth earbuds, screen-off/foreground-service behavior, interruption, network loss, and process death are exercised.
- Fresh install and database upgrade paths both pass.
- Root, cloud, and Android CI are green at the release commit.
- Twenty scripted golden-loop scenarios pass without nondeterministic state corruption.

### Operations

- Per-user daily/monthly and global provider-cost limits are active.
- Alerts exist for budget, provider error rate, latency, authentication failure, and stuck reservations.
- A support/reporting channel and severity policy are published to testers.
- Every build has release notes and known issues.
- Rollback and account-disable procedures have been rehearsed.

## Severity policy

| Severity | Examples | Alpha response |
| --- | --- | --- |
| S1 | Cross-user data exposure, secret disclosure, uncontrolled spend, unsafe repeated physical action | Disable affected service/feature immediately; notify affected testers |
| S2 | Session corruption, tool executed without required confirmation, repeated crash, deletion failure | Stop cohort expansion; patch or roll back |
| S3 | Degraded speech, stale UI, provider incompatibility, recoverable flow failure | Record, prioritize, include workaround |
| S4 | Cosmetic issue or minor copy problem | Normal backlog |

## Alpha telemetry

Collect operational metadata by default only when necessary to run the managed service:

- pseudonymous account/device/session identifiers;
- app/runtime version;
- request route and policy revision;
- timing, byte counts, token/usage counts, charge, and status;
- tool name/status without arguments when possible; and
- crash/error classification with secrets and content redacted.

Transcripts, images, audio, tool arguments, and memory contents are sensitive content. Do not collect them centrally by default. An explicit diagnostic upload should show scope, allow review where practical, attach a retention period, and create an audit event.

## Success criteria

The alpha is successful when:

- testers voluntarily return for multi-turn physical sessions;
- median explicit spoken turns feel responsive enough to sustain conversation;
- Live Context measurably avoids ask-time refresh without disproportionate cost;
- no session or memory crosses a workspace boundary;
- users can understand and recover from provider, permission, and budget failures;
- cost per active hour fits a plausible consumer subscription; and
- at least one repeatable task suggests a credible organization workflow without requiring a separate runtime.

## Explicitly deferred

- Always-listening wake word
- Autonomous background sessions
- iOS or shared cross-platform UI
- Modulo integration
- Enterprise fleet UI, SSO, and business-system connectors
- Regulated-industry claims
- Public paid launch

The closed alpha exists to make the consumer loop excellent and to collect the evidence needed for pricing and product direction.
