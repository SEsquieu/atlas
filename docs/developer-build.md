# Atlas developer build

`developer` is a build type sharing `main` with standard Atlas. Secure DEV uses `com.grinningfrog.atlas.dev`; LAN DEV uses `com.grinningfrog.atlas.lan.dev`. These coexist with standard apps, use separate Android data/Keystore storage, and require their own sign-in. They use the persistent alpha signing key for updates. The app label and System panel say DEV.

## Build and delivery policy

The primary purpose of DEV builds is targeted, per-use diagnostic tooling. Do not refresh or upload developer APKs after ordinary Core changes. Build one when a concrete investigation needs the probe panel or new instrumentation; reuse the existing diagnostic install otherwise.

Routine CI validates Secure standard and produces only its signed APK. LAN and developer validation/builds are opt-in via the Android workflow's manual `lan` and `developer` inputs. LAN is retained for deliberate trusted-network cleartext tests, not routine delivery. Flavor, transport, signing or developer-source changes should explicitly select the affected optional variants for validation. Drive delivery follows the selected build set; do not add extra variants by habit or delete prior builds without instruction.

## Request isolation

Open System → DEV · Diagnostics. Connect the provider on Inference first. Start with Minimal and “Reply exactly OK”, then compare Tools only and Search only. The combined preset adds Atlas system instructions, all installed tools, and hosted search. Individual tool selection can bisect schema problems. The instruction override and test prompt are independently editable. History/workspace checkboxes attach only state from the current session in the DEV app; import an archive through Data & logs for reproduction.

Minimal sends only model, store=false, stream=true, and a single user input. It omits instructions, Atlas tools, hosted web search, history, workspace, and images. It uses the same ChatGPT authorization manager, request serializer, HTTP transport, and stream parser as real inference. Hosted search is an explicit constructor option defaulting to normal behavior in standard sessions. There is no automatic route fallback in a probe, and no tool executor is called: model tool proposals are recorded and discarded. Enabling hosted search permits the provider to run its own read-only search. Probes use the selected model and provider timeout, and require an explicit tap to run. Leaving the panel or pressing Cancel cancels the probe.

Each probe writes an isolated DONE diagnostic session in DEV storage. Export probe logs uses the existing session archive/share flow. Optional history or workspace reproduction intentionally sends that data to the selected provider. Normal session state and workspace revisions are never modified by probes.

## Shared production diagnostics

ChatGPT exchanges emit `provider.http_diagnostic` with request metadata, response headers, and bounded failure metadata. Request bodies are represented only by size, SHA-256 fingerprint, item counts, instruction length, model, and tool names/types. Response diagnostics allowlist status, MIME type, provider request ID, edge ID, body kind and structured error code/type/parameter. Raw bodies, HTML, auth headers, cookies and credential fields are excluded. Error body sampling is capped at 8 KiB. Existing provider authorization/error policy and ambiguous-outcome protections remain in force; diagnostics do not retry ambiguous requests.

## Source separation and validation

The actual panel and probe builder/runner live under `app/src/developer`. Standard debug/release compile only the no-op `src/standard` hook. Removing developer build support does not require runtime flags throughout Core. CI tests/lints the selected variants, verifies persistent signing, and checks DEX markers to ensure developer implementation is absent from standard APKs.

The automated tests use mock HTTP responses, including 520. They prove request isolation and logging contracts, not availability of a live ChatGPT account. Phone probe exports supply that missing live evidence. New diagnostic modules should remain in the developer source set, reuse production entry points, and preserve the no-execution default.
