/* Portable orchestration policy. No timers, I/O, credentials, or Android bindings. */
"use strict";
globalThis.atlasRuntime = Object.freeze({
  bridge: 1,
  reduce(event) {
    if (event.bridge !== 1 || typeof event.request_id !== "string") throw Error("Unsupported bridge");
    const s = event.snapshot;
    if (!s || !Number.isInteger(s.step) || s.step < 0 || !Number.isFinite(s.elapsed_ms) || s.elapsed_ms < 0)
      throw Error("Invalid snapshot");
    if (event.type !== "before_inference") throw Error("Unsupported event");
    return {
      bridge: 1,
      request_id: event.request_id,
      action: s.step >= 8 || s.elapsed_ms >= 180000 ? "yield" : "infer",
      guidance: "Choose useful, bounded progress toward the user's goal. Use existing evidence before repeating work. After a successful stage, test the candidate before rewriting it unless an identified defect requires a correction. Make ordinary reversible design decisions yourself; ask the user only when their answer materially changes the task."
    };
  }
});
