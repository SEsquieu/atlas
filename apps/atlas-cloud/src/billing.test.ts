import assert from "node:assert/strict";
import test from "node:test";
import { usageChargeForRate } from "./billing";

test("usage charge is deterministic in integer microunits", () => {
  assert.equal(usageChargeForRate({ inputCreditsPerMillion: 2, outputCreditsPerMillion: 12 }, 1_000_000, 1_000_000), 14_000_000);
  assert.equal(usageChargeForRate({ inputCreditsPerMillion: .2, outputCreditsPerMillion: 1.2 }, 1, 0), 1);
});

test("output tokens use their independently configured rate", () => {
  assert.equal(usageChargeForRate({ inputCreditsPerMillion: 1, outputCreditsPerMillion: 10 }, 100_000, 10_000), 200_000);
});
