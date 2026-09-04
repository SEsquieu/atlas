# Security policy

Atlas handles cameras, microphones, location-adjacent physical context, credentials, tool execution, and potentially sensitive memory. Treat privacy boundary failures and uncontrolled physical actions as security issues.

## Supported versions

Until v0.1 is released, only the latest commit on the active alpha release branch receives security fixes. Debug builds and historical prototype adapters are not supported production releases.

## Private reporting

Use GitHub's **Report a vulnerability** flow in the repository Security tab. Do not open a public issue for a suspected vulnerability.

Include, when safe:

- affected commit/version and Android/cloud configuration;
- reproduction steps;
- whether another workspace, account, session, device, or task is affected;
- whether credentials, images, transcripts, memory, tool arguments, or physical actions are exposed;
- expected and observed behavior; and
- a minimal proof without real customer data.

If private vulnerability reporting is unavailable, contact the maintainer privately through the contact method on the GitHub profile and ask for a secure reporting channel. Do not send secrets or sensitive evidence in the initial message.

## Priority cases

The project treats these as highest severity:

- cross-workspace or cross-user data access;
- provider/service credentials present in a client or log;
- tool execution without required authorization or confirmation;
- replay or retry causing an unintended duplicate physical/external effect;
- budget bypass or uncontrolled provider spending;
- remote activation of camera, microphone, or Live Context outside visible lifecycle policy;
- diagnostic export/upload that exceeds user consent; and
- forged organization, session, task-run, or event attribution.

## Disclosure

The maintainer will acknowledge a valid report, coordinate a fix and release, and credit the reporter if requested and safe. Exact response-time promises will be added when Atlas has the operational capacity to meet them.

## Deployment responsibility

The repository is alpha software. Self-hosters are responsible for protecting provider keys, Supabase service credentials, Stripe credentials, signing keys, databases, network endpoints, and user data. Never use example credentials in production.
