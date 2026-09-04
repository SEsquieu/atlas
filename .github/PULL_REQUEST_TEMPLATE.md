## Problem and outcome

Describe the user/runtime problem and the result of this change.

## Verification

- [ ] Tests added or updated
- [ ] Build and typecheck pass
- [ ] Failure/restart behavior considered
- [ ] Documentation updated

## Boundary review

- [ ] Provider remains replaceable and does not own session state
- [ ] Memory/workspace/task scope cannot leak
- [ ] Tool authorization and idempotency are preserved
- [ ] Privacy, inference cost, latency, and physical-action risk are addressed
- [ ] No secrets, user content, or generated artifacts are included

## Compatibility

List schema, event, API, configuration, or durable-state migration implications. Write `None` when not applicable.
