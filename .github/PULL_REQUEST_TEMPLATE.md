## Tracking and scope

Fix ID: AF-___ (or explain why none applies)
Tracking issue: #___
Related discoveries: #___

List acceptance criteria addressed and any intentionally remaining work. Use `Closes #...` only when the entire issue, including required live verification, is satisfied; otherwise use `Refs #...`.

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

## Delivery and remaining verification

- Source commit tested:
- Checks run and results (include failures or checks not run):
- Phone/provider verification still needed:
- Backlog status update and evidence link:

Merging code does not automatically establish live verification or request a new APK. Maintainers generate installable builds separately. Agent-assisted changes have the same evidence and DCO requirements as other contributions.
