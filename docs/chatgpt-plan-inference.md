# Sign in with ChatGPT and plan inference

Atlas Core can use eligible inference included with a user's ChatGPT plan without asking for an OpenAI API key. This is an additive provider: on-device llama.cpp, LAN endpoints, BYOK providers, Atlas Managed, and existing deterministic fallback continue to work independently.

## Boundary

ChatGPT is an inference provider, not the Atlas agent. Atlas continues to own identity, sessions, full context assembly, memory, observations, tool definitions, permission and confirmation policy, tool execution, routing, retries, lifecycle, and audit events. OAuth credentials go directly from Atlas Android to OpenAI; Atlas Managed never receives them.

Signing in grants only the identity and plan-usage scopes shown during OpenAI authorization. It does not give Atlas access to ChatGPT conversations, ChatGPT memory, API keys, or unrelated account data.

## Authorization flow

1. Atlas creates or reuses a stable host ID: an RFC 9278 JWK-thumbprint URI derived from an Android Keystore P-256 key.
2. Atlas binds a one-shot HTTP listener to an ephemeral `127.0.0.1` port, creates fresh state, nonce, and S256 PKCE values, then opens OpenAI authorization in the system browser.
3. First registration uses `dynamic_agent_client`; later authorization reuses the issued `oaiapp_...` client ID.
4. Atlas validates callback state before code exchange, then validates the ID-token RS256 signature against OpenAI JWKS, issuer, audience, expiry, nonce, and subject continuity.
5. Atlas requires `chatgpt.tokens.use.direct`, discovers the account's visible models, and only then exposes the provider to routing.

The listener is loopback-only, accepts one transaction, has a bounded timeout, and closes immediately. State prevents forged callback acceptance; PKCE prevents another local process from redeeming an intercepted code.

## Credential storage and lifecycle

The issued client ID, validated account identity, access token, rotating refresh token, retained ID token, scopes, expiry metadata, and model selection are stored as one Android Keystore AES-GCM encrypted record. Refresh is serialized per process, and the complete replacement record is committed atomically. Tokens are excluded from logs, telemetry, session export, URLs, and provider endpoint records.

Access tokens currently last one hour. Refresh tokens currently last 30 days and rotate on successful refresh. Disconnect attempts OpenAI revocation, stops local use, and clears bearer credentials. Users can also disconnect Atlas in ChatGPT settings.

The host key and encrypted authorization survive normal app restarts. Clearing app data or uninstalling Atlas removes the local authorization; reinstalling creates a new host and requires authorization again.

## Responses translation

`ChatGptPlanBackend` uses only `POST https://api.openai.com/v1/responses` with the OAuth access token. Every request uses `store: false` and `stream: true`, sends the full Atlas-selected history in `input`, and puts the Atlas system contract in `instructions`. It omits unsupported plan-route fields including temperature, output-token limits, metadata, conversation IDs, and `previous_response_id`.

The backend maps Responses SSE events into Atlas `TextDelta`, `ToolCallDelta`, and `Completed` events. A response succeeds only after `response.completed`; incomplete, failed, malformed, cancelled, and interrupted streams preserve Atlas outcome-ambiguity semantics. OpenAI function calls become ordinary `ToolCallProposal` values and must pass through `ToolHarness`, risk policy, confirmation, idempotency, and audit like calls from every other provider.

Text, image input, client-side function tools, and streaming are used only where the selected account model accepts them. Unsupported-capability errors are safe routing failures before output and can fall through to another configured Atlas provider.

## Eligibility and troubleshooting

Availability, models, workspace policy, and usage are controlled by OpenAI and can change. Atlas does not promise unlimited inference or infer a reset time. A valid identity without the direct plan-usage scope is not routable.

- **Browser never returns:** complete authorization before the three-minute callback timeout. VPN/browser loopback filtering can interfere.
- **Plan unavailable:** the account, workspace, region, or policy may not be eligible, or plan permission was declined.
- **Usage limit reached:** review usage and app limits in ChatGPT settings or use another Atlas route.
- **Authorization expired:** select Continue with ChatGPT again.
- **Model rejects images or tools:** choose another eligible model or let Atlas use its next capability-compatible fallback.

This integration uses only OpenAI's public Sign in with ChatGPT open-source/local flow. It does not use cookies, private ChatGPT backend endpoints, browser-session extraction, or undocumented APIs.
