# Activating a candidate for manual testing

When behavior cannot be verified with the installed test surface, an explicit request to test it manually can be proposed through workspace_accept_unverified. The tool always uses native user confirmation and requires an exact quote from the latest user message in the durable task. It accepts only exact outstanding criteria for the current target and immutable candidate/hash.

Core records user_accepted_unverified, the source message, confirmation operation and criteria in durable task state. No test is marked passed. Changed candidate bytes require new acceptance. A resumed slice retains acceptance of the same bytes. Activation still checks pure fixtures, required device simulation, scope, revision/data freshness, migration compatibility, startup, permissions and unknown effects. This control accepts uncertainty about requested behavior; it grants no device authority.

The model should report which checks passed and which were accepted for manual testing. Ordinary optional screenshot unavailability after committed delivery needs no acceptance control. Do not manufacture a quote or loop on a rejected handoff.
