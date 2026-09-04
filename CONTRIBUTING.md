# Contributing to Atlas

Atlas welcomes focused contributions that strengthen a provider-independent physical-agent runtime. The project is preparing for v0.1; compatibility, deterministic behavior, privacy, and failure handling matter more than feature count.

## Before opening a change

- Use a GitHub discussion or issue for a large new subsystem, public interface, storage migration, provider contract, or behavior that can cause a physical-world effect.
- Report security vulnerabilities through the private process in [`SECURITY.md`](./SECURITY.md), not a public issue.
- Keep Atlas Core independent from provider-owned sessions, model names, and proprietary device bridges.
- Do not submit production credentials, user recordings, private images, customer data, or generated build artifacts.

Small fixes, tests, and documentation improvements can go directly to a pull request.

## Development checks

TypeScript/Core:

```bash
npm ci
npm run build
npm run typecheck
npm test
```

Android:

```bash
cd apps/atlas-android
./gradlew testDebugUnitTest assembleDebug
```

Managed-inference gateway:

```bash
cd apps/atlas-cloud
npm ci
npm test
npm run typecheck
npm run build
```

Run `npm run check:public` from the repository root before submitting. Tests requiring a real phone or provider must be opt-in and must never consume a contributor's paid inference unexpectedly.

## Pull requests

A useful pull request:

- explains the problem and product impact;
- separates deterministic runtime behavior from provider suggestions;
- includes tests for success, failure, restart, and budget behavior where applicable;
- documents schema, event, configuration, or API changes;
- avoids unrelated formatting and generated files; and
- calls out privacy, cost, latency, and physical-action implications.

Public contracts should be additive during v0.1 whenever practical. Database migrations must preserve existing local sessions and be safe when upgrading from every supported schema version.

## Developer Certificate of Origin

Atlas uses the [Developer Certificate of Origin](./DCO), not a Contributor License Agreement. Sign each commit with:

```text
Signed-off-by: Your Name <your-email@example.com>
```

`git commit -s` adds the line automatically. The sign-off certifies that you have the right to submit the contribution under the repository license.

## Architecture invariants

Contributions must preserve these boundaries:

1. Atlas owns the session and lifecycle.
2. Providers are replaceable and do not own durable physical state.
3. Observations retain source time, freshness, confidence, and provenance.
4. Memory enters a session only through explicit scope and admission policy.
5. Tools execute only after schema validation, policy, confirmation, and budget checks.
6. Events are sufficient to explain consequential runtime decisions.
7. BYOI remains a complete supported route.
8. Enterprise extends versioned Core contracts rather than creating a private runtime fork.

See [`docs/product-structure.md`](./docs/product-structure.md) for the open/commercial boundary.

## Licensing

Unless a file states otherwise, contributions are accepted under the Apache License 2.0. Dependencies and incorporated material must have compatible, documented licenses. Avoid copying model output, datasets, media, or code when its rights are unclear.
