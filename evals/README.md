# Atlas behavioral regression fixtures

This directory preserves failures found through real Atlas use without pretending to be a large model benchmark. A fixture separates facts Core supplied from behavior an endpoint produced and behavior Atlas wants to measure.

Run the deterministic fixture check with:

```bash
npm run test:evals
```

The validator checks schema, stable IDs, turn ordering, provenance, and expectation references. It does not call a model and cannot declare a model correct. Endpoint replay is a future runner: it should submit the fixture through an explicitly selected endpoint, preserve raw output and route telemetry, and score dimensions independently.

## Dimension vocabulary

- `context_retention`: recalls facts that were actually supplied.
- `state_mutation`: applies corrections or renames without losing prior provenance.
- `formatting`: follows and later releases temporary output constraints.
- `provenance`: distinguishes user facts, observations, tool results, summaries, and model claims.
- `unsupported_inference`: avoids adding a causal or quantitative bridge absent from evidence.
- `entailment`: determines whether stated premises logically support a conclusion.
- `self_correction`: revises a claim when challenged and identifies the bad assumption.
- `freshness`: respects evidence age/suitability rather than treating old perception as current.
- `provider_switching`: preserves Core-owned state when the reasoning endpoint changes.
- `interruption` and `late_response`: preserve delivery and lifecycle truth across timing boundaries.

## Adding a fixture

Add one JSON file under `evals/fixtures`, give every turn a stable ID, state the source of each fact, and attach expectations to the turn where they can be judged. Preserve observed bad behavior in `observed_output`; put the desired invariant in the expectation. Never store real credentials, private endpoint URLs, session exports, images, or personal data in fixtures.
