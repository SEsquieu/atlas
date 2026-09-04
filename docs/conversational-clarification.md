# Conversational clarification

Atlas treats ambiguity as runtime state, not prompt etiquette. A provider may identify uncertainty, but Atlas Core owns whether a question is pending, what it blocks, how a later reply binds to it, and what the audit trail says happened.

## Behavioral contract

1. Use current conversation and physical observations to resolve the reference when possible.
2. Make an unstated assumption only when it is low-risk, cheap, and easy to reverse.
3. Ask one focused question when ambiguity materially changes physical guidance, safety, cost, authority, task direction, or tool effects.
4. Treat short fragments such as “the black one” as possible answers to the pending question.
5. Answer unrelated tangents briefly without silently discarding the task or question.
6. Do not execute or forward physical tool proposals while a blocking question is unresolved.

This makes the language surface flexible while keeping the execution boundary deterministic.

## Durable state

`PendingClarification` records a Core-generated ID and source turn; question, reason, and ambiguity class; optional choices; blocking state; observation/freshness context; and creation, deferral, and expiry data.

The lifecycle is reconstructed from `clarification.requested`, `clarification.retained`, `clarification.deferred`, `clarification.resolved`, `clarification.abandoned`, `clarification.expired`, and `clarification.superseded` events. User utterance events identify the pending question at turn start. Provider violations are recorded as `provider.result_constrained`.

## Provider contract

TypeScript Core exposes `clarification` to request one question and `clarificationDisposition` to explicitly resolve, defer, or abandon it. Android exposes the same lifecycle as the `atlas_clarification` function tool. It is a control signal handled by Core and never a physical tool. Request is terminal for that model step; resolve or abandon returns a tool result so reasoning can continue; defer ends the tangent turn while preserving the question.

Tool-capable providers get the deterministic contract. Text-only providers still receive the policy and transcript but cannot create structured durable clarification state in the current alpha. Atlas does not infer control state from hidden text markers or provider-specific prose.

## Safety and delivery

A question defaults to blocking. Atlas can converse and refresh observations while it remains pending, but suppresses physical tool proposals until the provider explicitly resolves or abandons it. Android displays the question separately and accepts replies through normal text or push-to-talk.

Clarification establishes what the user means; confirmation authorizes an already-understood consequential action. A turn may require both, in that order.

## Release tests

Tests cover creation and replay, tangent retention, fragment resolution, deferral, expiry, supersession, mismatched IDs, and blocked tool proposals. Provider replacement while a question is pending remains an integration release case.
