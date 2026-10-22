# Alpha.33 phone assessment

The October 22 MazeGen session used bundled core 0.1.0. The original live workspace remained intact; no activation call was attempted. One extra draft was created and then reused. Source changes passed pure fixtures and a corrected Generate→Solve UI path, but an invalid-dimension criterion remained unverified because the sliders could not submit that input.

## Findings

- An already-live candidate retained its historical base. Structural validation still directed testing/activation, and retesting correctly hit a revision conflict. Return lifecycle-specific recovery instead.
- A candidate file hash was used without `parent_candidate_id`, so the edit was correctly checked against live source and rejected. Return source ancestry explicitly.
- Discovery rejected the native confirmation required for accepting unverified behavior, even after local work resumed. Let installed local adapters reach the existing approval gate; never interpret research as approval.
- The model tried a replacement draft and repeated source/target reads. Keep the installed task target and explain the next executable step.
- Runtime source editing was not installed in alpha.33. Claiming it could fix the host would have been false. Alpha.34 adds bounded local JavaScript proposals; native rules still need an APK.

## Measurements

85 core decisions: first call 84.797ms; 84 warm samples, p50 3.510ms, nearest-rank p95 7.603ms, maximum 25.646ms. Total core evaluation 454.329ms, excluding mutex queue wait.

90 completed provider responses consumed 460.940 seconds cumulative provider latency, 2,045,270 prompt tokens and 10,743 completion tokens. Of these, six compactions consumed 61.294 seconds and 57,430 prompt tokens. All reported requested/served effort was Low. One additional provider request returned 503. These cumulative measurements are not end-to-end turn latency.

The task recorded 85 model steps, 81 dispatched tools and 438.664 seconds active execution over 15 slices. There were 24 source reads. Bridge overhead was small relative to provider calls and churn; this run does not establish a controlled native-versus-bundled comparison. Raw session data is intentionally excluded from the public repository.
