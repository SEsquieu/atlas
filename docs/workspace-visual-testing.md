# Autonomous workspace visual testing

Atlas can optionally exercise an immutable staged HTML/CSS/JS workspace on an app-private Android display while the user stays in the session. The model chooses this work when visual, layout, canvas or interaction evidence is useful. There is no new universal activation requirement and no Snake-specific workflow.

## Host boundary

`WorkspaceVisualService` is nonexported and runs in `:workspace_visual`. It initializes a separate WebView data-directory suffix before WebView use. Candidate JavaScript runs in Chromium's isolated renderer, separate from the foreground app's WebView profile. The service never opens the live Atlas database, installs real capability adapters, injects Android-wide input, obtains MediaProjection or requests overlay authority.

The service creates an `OWN_CONTENT_ONLY | PRESENTATION` virtual display backed by a continuously drained RGBA ImageReader surface. A hardware-accelerated `Presentation` hosts a visible attached Compose/WebView hierarchy on that display. The display is private, does not mirror the physical display, and the window is not focusable. This is an attached compositor window rather than a detached/invisible WebView or software `WebView.draw` approximation.

Each open first renders a trusted solid-color calibration package through the production package loader. A Chromium visual-state callback and subsequent hardware frame-commit callback precede PixelCopy of the Presentation window. Several captured pixels must match the calibration color. Failure closes the renderer and reports unavailable evidence; there is no software screenshot fallback. The candidate then uses the same rendering path. A successful calibration establishes this device/provider's current capture path, not general OEM qualification or correctness of the candidate.

The production `WorkspaceWebPresentation` supplies packaged-only resource loading, CSP, opaque-origin/main-frame checks, bridge quotas, blocked network/browser/device APIs, muted audio, renderer monitoring and termination. `WorkspaceTestStorage` supplies a disposable in-memory SQLite database. The existing simulated BLE adapter handles declared BLE operations. Mock grants are restricted to the TEST principal and never confer live native permission.

## Authoritative viewport

The foreground Scaffold records its actual content bounds after Atlas controls, bottom navigation, system bars and IME layout. The profile includes current window and physical display-mode pixel dimensions (separately labeled), density/DPR input, font scale, orientation, system/cutout/IME insets and touch features. It is unavailable without an attached, focused, unlocked Atlas Session/Workspace window. System has different native controls and cannot supply the default workspace profile.

The private display uses the measured content dimensions and the same production toolbar, revision label and capability-bar composables. The WebView's actual native bounds and JS `innerWidth`, `innerHeight`, `devicePixelRatio` and visual viewport are returned. Native chrome is laid out with the user's font scaling; it is not estimated from physical resolution. BLE chrome has a simulated label; live consent dialogs and variable device names remain separate physical acceptance cases.

Optional `width_css` and `height_css` create explicitly labeled alternative responsive probes. Explicit height reserves bounded native-chrome space and crops captures to the WebView. Resource budgets still apply. Generated apps should use responsive CSS and the actual reported viewport, not embed the current phone dimensions. Rotation, keyboard/content-area changes, density or font-scale changes invalidate an open instance's device profile: close and reopen.

## Agent contract

`workspace_visual_status` is a small discoverable diagnostic. Calling it reveals `workspace_visual_test` in subsequent task inference requests, keeping the larger action schema out of unrelated turns.

| Action | Input | Evidence/effect |
| --- | --- | --- |
| `open` | `workspace_id`, `candidate_id`, optional `width_css`, `height_css` | Validate immutable staged bytes; create one task-owned renderer; return calibration, profile, handle and measured layout. |
| `inspect` | `instance_id` | Bounded geometry, styles, visibility, disabled state, scroll extents, ancestor/viewport clipping, center-point occlusion, resources, JS/bridge errors. |
| `capture` | `instance_id` | Frame-fenced PixelCopy; persist a WORKSPACE_UI observation and attach it to inference under observation permission. |
| `interact` | `instance_id`, `steps` | Up to16 validated actions/assertions against the persistent DOM. |
| `finish` | `instance_id` | Persist optional `visual_render` receipt bound to candidate hash, base revision and live-data version. |
| `close` | `instance_id` | Release renderer, storage, window, composition, display, surface, callbacks and file descriptors. |

DOM actions reuse the existing batch executor's fixed scripts: click, input, select, set_checked, bounded wait_text and text/value/visibility/count/attribute assertions. Counts and object-valued attribute expectations use the existing schema normalization. Only assertions may carry `criterion`. `tap` and `swipe` dispatch native MotionEvents solely to the test WebView, using measured CSS-to-native coordinates. `scroll` uses fixed window scrolling; `key` emits fixed synthetic DOM key events. Text input does not summon the phone keyboard. No arbitrary evaluation, OS gesture or model-supplied file path is exposed.

A finish passes only after at least one assertion, no failed action/assertion, no sampled runtime/resource/bridge error, and a successfully ingested screenshot after the most recent interaction batch. It records observations and exercised criteria, not aesthetic judgments. Clipped/occluded geometry can be intentional; the model must interpret it. Bounded diagnostics are sampled evidence, not a proof that every possible JS error or control was exercised. After a failed test, open a fresh instance to establish a clean passing run.

Full geometry remains in the durable check; finish and activation responses carry compact summaries so successful verification does not repeatedly spend context on the same DOM. Visual criteria may satisfy the task's named behavioral acceptance criteria. Persistence still needs the existing commit/reload/assert_record batch check. Existing pure-fixture, startup, BLE simulation, revision, epoch, rollback and permission gates remain intact. Activation attaches the newest exactly matching passing visual receipt when present and succeeds without one when ordinary requirements are met. No native code decides which viewport/interaction sequence or design is appropriate.

## Lifetime, concurrency and recovery

One instance exists app-wide. Other tasks cannot inspect, interact with, capture or close it. A second open reports busy; close before switching candidates. Handles belong to the durable task rather than the inference slice, preserving application state through normal continuation. Interaction request IDs suppress duplicate delivery within the bounded renderer process cache.

Host journals retain handle ownership/candidate/profile metadata; they do not pretend to persist DOM or JavaScript state. Process death or renderer failure expires the handle and requires reopening. No gesture is silently replayed to reconstruct a lost state. Candidate/base/data changes invalidate evidence. Testing cannot update the live revision pointer or live records.

Limits: 4 million display pixels, 4096 pixels per axis, one instance, 128 operations, 5 minutes idle and 20 minutes absolute lifetime. After 30 seconds idle the remote WebView pauses timers and detaches the display surface; the next operation resumes and waits for a frame. This affects only the test process. Task cancellation/pause/completion, session stop, Activity stop, explicit close, service destruction, errors and timeouts retire test resources. The feature is for background development while Atlas stays foreground, not unattended execution while the phone is locked or another app is active.

Candidate packages and PNGs cross Binder through host-created file descriptors, avoiding large Binder payloads; temporary files are deleted and descriptors closed. JSON output and probes are bounded. Capture startup/operation latency, service PSS, process ID, candidate/handle, receipt and observation identifiers are auditable through `workspace.visual_*` events. Measure device memory/thermal/battery behavior before increasing limits; no unmeasured energy or latency claim is made.

## Qualification and real-device acceptance

`WorkspaceVisualDeviceTest` exercises the real service/private-display/PixelCopy path rather than a mock screenshot. The tests also exercise the agent-facing tools, screenshot observations, cross-slice state, matching activation evidence, idempotent close, canvas colors, state changes, failed assertions, owner isolation, cancellation during a hung startup, process death, disposable storage, mocked BLE without OS permission changes, and blocked browser APIs. CI saves an actual isolated PixelCopy PNG alongside existing device evidence. Existing workspace and replaceable-runtime instrumentation remains required. CI on API35 and36 is necessary but does not qualify Samsung's compositor.

On the target Galaxy S22, record APK source/signature/version, Android/OneUI, WebView package/version, physical resolution, density/font scale, orientation, returned profile, calibration and operation timings. Then:

1. Stay on Session. Ask Atlas to create a Snake game and use visual testing when needed. Confirm the workspace never opens, focus/keyboard/navigation remain unchanged and intermediate renders are not visible on the physical display.
2. Capture several nontrivial candidate states: CSS gradient, canvas game board, text, controls and animation. Compare the activated app's screenshot at the same profile/state to isolated captures. Calibration alone does not verify dynamic canvas fidelity.
3. Use native tap/swipe, DOM text input and game keys. Verify state transitions with screenshots plus exact assertions. Try overflow/clipping/covered controls and check measured evidence; intentional scrolling must not be treated as a mandatory failure.
4. Repeat in landscape, enlarged font/display settings, gesture/button navigation and keyboard-visible states. Verify profile invalidation/reopen and responsive alternatives. Test BLE with only mock hardware; verify the active workspace's live radio/grants/records never change.
5. Keep a foreground application active while a test renderer crashes or loops. Confirm only the test renderer expires and the live app, session input and saved records remain usable. Cancel/pause a task mid-frame/gesture and close/reopen repeatedly.
6. Exercise idle resume, task slicing, TTL expiry, Activity background/lock, service/process death and relaunch. Old handles must fail honestly; no stale screenshot/receipt may authorize a changed candidate.
7. Measure cold/warm startup, capture/interaction p50/p95, PSS (main/visual/Chromium), frame responsiveness, thermal/battery impact and resource counts over at least 20 open/close cycles. Use `dumpsys display`, `meminfo`, `gfxinfo` and logcat. Check no lingering virtual displays, windows, WebViews, temporary package/PNG files or callbacks.

A physical-device failure is an open milestone defect, not grounds to relabel DOM output as painted evidence. Track qualification and remaining gaps in issue #75.

## Android implementation references

The compositor choices follow Android's platform contracts: [private virtual-display flags and release behavior](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/hardware/display/DisplayManager.java), [Presentation display-context/window lifecycle](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/app/Presentation.java), [WebView visual-state prerequisites](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/webkit/WebView.java), [hardware frame commits for PixelCopy](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/ViewTreeObserver.java), and [PixelCopy window surface requirements](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/graphics/java/android/view/PixelCopy.java). These contracts motivate the attached private window, hardware commit fence, drained consumer surface and fail-closed calibration; device qualification remains necessary.
