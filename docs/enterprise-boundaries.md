# Consumer-first, organization-ready boundaries

Atlas ships its first complete experience as a personal Android physical agent. The runtime model does not assume that one user, phone, session, and task are the same thing. A personal installation is the smallest deployment of the same ownership graph used by a shared station or fleet.

## Identity and ownership

| Concept | Consumer default | Organization deployment |
| --- | --- | --- |
| Workspace | local personal workspace | cloud organization tenant |
| Principal | phone user | user, service identity, or device |
| Placement | optional device | site and station |
| Session | one person's conversation | interaction window within a task run |
| Task run | optional goal | durable execution of a procedure revision |
| Billing | personal organization balance | shared organization balance with actor attribution |

Cloud personal accounts are one-member organizations. Authentication identifies a principal, while the organization owns durable data and managed-inference funds. An `X-Atlas-Organization-Id` request is accepted only after server-side membership resolution; omitting it selects the caller's personal organization.

## State separation

Atlas keeps five forms of state distinct:

1. Physical state: observations, timing, confidence, stability, and evidence.
2. Session state: current interaction and lifecycle.
3. Task state: goal, procedure revision, current step, external work reference, and completion.
4. Scoped memory: session, task, principal, workspace, or environment knowledge with provenance and expiry.
5. Policy: resolved inference, retention, interaction, and tool constraints.

The legacy `working`, `durable`, `environmentNotes`, and `taskProgress` arrays remain readable during v0.1 migration. New integrations should use scoped memory. Core filters memory by the active ownership graph so another organization or task cannot enter a provider turn accidentally.

## Policy resolution

Policy layers resolve from broadest to narrowest:

```text
defaults -> organization -> site -> station -> procedure -> assignment -> task run
```

Ordinary settings overlay. Restrictive cloud/media decisions remain restrictive, cost and retention ceilings take the lowest value, tool denies accumulate, and multiple explicit allowlists intersect. This prevents a narrower profile from silently restoring a capability denied by a broader safety policy.

## Event envelope

Every new Core event can carry workspace, organization, principal, site, station, session, task-run, procedure-revision, correlation, and causation identifiers. File-backed Core storage enriches missing scope from the authoritative session before append. Android persists workspace and task scope alongside its monotonic event sequence.

The cloud schema stores runtime events separately from materialized sessions. That supports deterministic replay, audit export, task-level measurement, and station-level debugging without asking the model to own history.

## Database isolation

The Supabase tenant graph includes organizations, memberships, sites, stations, devices, procedure revisions, task runs, runtime sessions, policies, scoped memory, events, organization billing, credit ledger, and inference usage.

All exposed-schema tables have RLS enabled. New tables remain closed to direct `anon` and `authenticated` Data API access while Atlas Cloud is the write boundary. Membership policies provide defense in depth for deliberate future exposure. Composite organization foreign keys prevent cross-tenant site, station, device, procedure, task, and session relationships even through privileged server code.

## Deliberate non-goals for the consumer alpha

This foundation does not add SSO, SCIM, MES/ERP connectors, procedure authoring, fleet administration, supervisor dashboards, or compliance claims. Those are product surfaces. The separation here exists because retrofitting tenant ownership, scoped memory, durable tasks, and audit identity after consumer release would be dangerous and expensive.
