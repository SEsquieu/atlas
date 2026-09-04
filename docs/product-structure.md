# Atlas product and source structure

Status: adopted direction for the v0.1 closed alpha. Commercial prices remain planning assumptions until real usage data exists.

## Product thesis

Atlas is the runtime in which replaceable intelligence remains situated, current, observable, resource-aware, and constrained while interacting with the physical world. A provider proposes language and tool calls. Atlas owns the physical session, durable state, perception, freshness, memory admission, tool policy and execution, user interaction, audit trail, budgets, and lifecycle.

The project uses an open-platform model with paid operated services and a commercial organization layer. Open source is an adoption and trust surface, not a limited trial.

## Three product layers

| Layer | Distribution | Customer promise | Revenue role |
| --- | --- | --- | --- |
| Atlas Open Platform | Apache License 2.0 | A complete personal physical-agent runtime, reference Android app, SDK contracts, replay, and BYOI | Adoption, ecosystem, trust, contributors |
| Atlas Managed Inference | Atlas-operated service | Working inference without provider setup, with task-aware routing, metering, limits, and support | Consumer subscription and usage revenue |
| Atlas Enterprise | Commercial software and services | Organization, fleet, procedure, policy, integration, audit, and operational control around the same runtime | B2B platform, deployment, and support revenue |

### Atlas Open Platform

The open platform must be independently useful. It includes:

- Atlas Core session, event, memory, freshness, policy, tool, replay, and budget primitives;
- the Android personal/reference experience, including camera, speech, streaming, barge-in, Live Context, and local persistence;
- provider and device contracts;
- direct local, LAN, and user-configured cloud inference;
- capability-oriented routes such as `fast`, `vision`, `reasoning`, and `fallback`;
- deterministic tests, fixtures, and observability contracts; and
- documented extension points for providers, devices, tools, storage, and policy.

Open Atlas may not require an Atlas account or Atlas-operated service. It must not contain artificial limits intended only to force conversion.

### Atlas Managed Inference

Managed inference is optional. It owns:

- authentication and entitlement;
- provider credential custody;
- evaluated capability-to-model routing;
- provider compatibility and failover;
- request budgets, rate limits, and abuse controls;
- reserve-before-call and settle-after-call metering;
- subscription allowances and additional usage blocks; and
- support and operational monitoring.

It does **not** own the physical session, heartbeat, device, tool execution, or durable personal agent state. The gateway receives only the bounded request context selected by Atlas Core.

The public repository currently contains the reference gateway implementation under Apache-2.0. That is intentional: customers pay for a reliable operated service and provider usage, not secrecy around an HTTP proxy. Production credentials, deployment configuration, fraud systems, internal operations, and customer data are never committed.

### Atlas Enterprise

Atlas Enterprise extends the open runtime through versioned interfaces. It does not fork Core. Commercial surfaces include:

- organizations, roles, service principals, SSO, and SCIM;
- device and station enrollment, fleets, remote configuration, and staged rollout;
- sites, procedures, revisions, assignments, and task runs;
- organization policy authoring and inheritance;
- centralized event ingestion, audit export, retention, and redaction;
- supervisor review, escalation, and intervention;
- pooled budgets, cost allocation, and usage reporting;
- MES, ERP, WMS, knowledge-base, and identity integrations;
- evaluation dashboards and process analytics;
- deployment assistance, support, and contractual service levels; and
- validated packages for regulated environments when Atlas can substantiate them.

Enterprise policy can restrict an open runtime, but must do so through the same visible policy and event contracts available to community deployments.

## Repository topology

The source boundary is enforced by repository visibility and dependency direction, not by long-lived branches or hidden folders in the public repository.

| Repository | Visibility | Responsibility |
| --- | --- | --- |
| `SEsquieu/atlas` | Private during alpha preparation; public after the release checklist | Complete open runtime, Android app, BYOI, public contracts, tests, and reference gateway |
| `SEsquieu/atlas-managed` | Private | Operated inference admission, production routing configuration, commercial metering, abuse controls, service operations, and infrastructure |
| `SEsquieu/atlas-enterprise` | Private | Organization, fleet, station, procedure, policy, connector, audit-administration, and enterprise-support control plane |

Production infrastructure remains in `atlas-managed` until its access controls or deployment lifecycle justify a fourth repository. Closed-alpha access is enforced by authenticated service entitlements and server-side budgets, not by withholding the device runtime source.

The public repository remains organized as:

```text
apps/
  atlas-android/             open reference consumer app
  atlas-cloud/               open reference managed-inference gateway
packages/
  atlas-core/                runtime contracts and deterministic policy
  atlas-config/              configuration contracts
  atlas-cli/                 development and inspection tools
  atlas-device-android/      legacy TypeScript device adapter work
  atlas-provider-openclaw/   explicitly legacy proof adapter
  atlas-test-harness/        replay and scenario tooling
docs/                        architecture, policies, operations, and releases
```

The two private repositories begin as tested boundary packages rather than speculative products. Their manifests declare compatible public protocol ranges, prohibit source-checkout imports, and reserve implementation decisions until the consumer runtime and public contracts stabilize.

## Compatibility rules

1. Enterprise consumes released Core contracts; it does not copy or patch a private Core fork.
2. Managed inference is one provider route, never session authority.
3. Public extension interfaces remain vendor-neutral and capability-oriented.
4. Enterprise-only metadata must round-trip through open event and policy envelopes without changing personal behavior.
5. A personal workspace is the smallest valid deployment of the same ownership model used by an organization.
6. Cloud unavailability must not corrupt or strand a local session.
7. BYOI remains available when managed allowance is exhausted.
8. Private repositories consume tagged packages or generated clients from released public schemas; they may not use `file:`, branch, or workspace links into a neighboring Atlas checkout.
9. Production infrastructure stays with Managed until a separate security or deployment boundary is demonstrated.

## Licensing and contribution model

- Repository code is Apache-2.0 unless a file says otherwise.
- Contributions use Developer Certificate of Origin sign-off; v0.1 does not require a CLA.
- Dependencies retain their own licenses.
- The software license does not grant rights to Atlas names, logos, hosted services, credentials, or customer data.
- No contribution can silently move a public Core capability behind an Atlas-operated service.

Apache-2.0 provides broad commercial use rights and an explicit patent grant. A future license change applies only to code for which the project has the necessary rights; accepted Apache-2.0 contributions cannot be retroactively made proprietary.

## Decision test for new features

Put a feature in the open platform when it is required for one person to run, understand, extend, or safely constrain an Atlas physical loop. Put it in Enterprise when its primary value is coordinating organizations, fleets, repeatable business processes, central governance, or contractual operation. Put it in Managed Inference when its value comes from Atlas operating provider access and routing on the customer's behalf.

When uncertain, prefer an open contract and a replaceable implementation. Enterprise can sell coordination and operation without weakening the runtime developers build on.
