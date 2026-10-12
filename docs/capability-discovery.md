# Generic read-only capability discovery

When an objective cannot be completed with the supplied tools, the Core prompt permits bounded research using an already available, authorized research mechanism. The agent decides whether a capability is missing and what to search for; there is no service name, domain, scenario or candidate list in the policy.

`atlas_capability_discovery` records the blocker and source-backed candidate assessment. It is an audit control, not a service connector. Local search stays with the existing executor; hosted search stays with the existing provider. No research mechanism is installed or enabled by this change.

## Evidence in exported session logs

Filter `events` for `event_type` beginning with `capability.` and group by `data_json.turnId`:

| Event | Meaning |
|---|---|
| `capability.blocked` | Model-reported objective and missing capability |
| `capability.missing_identified` | Explicit missing-capability assessment |
| `capability.search_attempted` | Existing research executor invoked, or hosted research observed in a provider response |
| `capability.search_completed` | Outcome and returned evidence URLs |
| `capability.candidate_evaluated` | Candidate, applicability, requirements/unknowns and cited evidence |
| `capability.candidate_proposed` | Promising or unverified candidate proposed; no authorization granted |
| `capability.no_candidate` | No supported candidate or research unavailable |
| `capability.discovery_denied` | Execution-time attempt to leave the read-only research boundary |

Hosted search is only observable after a provider response. Its events explicitly set `observed_after_response=true`; their timestamp does not pretend to be the moment the external search began. A provider failure before returning tool-use evidence still has the existing provider failure log, but may not expose an individual hosted-search attempt.

Candidate source URLs must have appeared in this turn's recorded research. Assessments are model judgments, not proof that the service is available or connected. External content is untrusted evidence. Candidate proposals always report `authorization_granted=false` and `candidate_invoked=false`.

## Execution boundary

For the remainder of a declared discovery turn, the harness only permits audit/conversational controls and installed research adapters explicitly opted into discovery with read-only risk and no additional confirmation requirement. It rechecks this boundary at execution so calls prepared in the same batch cannot bypass it. Ordinary permission policy still applies. The next user turn retains its ordinary tool policy; discovery never modifies permissions or registers a capability.

The policy forbids autonomous installation, account creation, authorization, secret disclosure, payments, permission grants, downloaded-code execution, and acquisition/invocation of candidates. Those require a separate explicit authorization path. This change does not add such an acquisition path. The prompt also prohibits including credentials or private session data in public research queries; queries remain model-generated, so this is not a general data-loss-prevention system.

Existing turn, per-tool and inference budgets remain in force. In a fresh-session test, ask for the original objective without naming a service or prescribing a search. Inspect whether the model independently records the blocker, researches, and proposes a source-backed candidate. Passing the deterministic harness tests does not establish that every model will choose this behavior; that is the experiment.
