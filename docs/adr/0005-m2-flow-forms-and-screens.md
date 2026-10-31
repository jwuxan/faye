---
status: accepted
date: 2026-10-30
decision-makers: Josh Wu
---

# M2 flow forms, predicates and screens

## Context and Problem Statement

M2 adds the forms `choose`/`opt`, `when`, `retry` and `set`, structured predicates, and the screens `method_picker`, `passkey`, `otp`, `otp_enroll`, `consent` and `error` ([milestones.md](../roadmap/milestones.md)). [dsl-and-ir.md](../roadmap/dsl-and-ir.md) gives each a one line meaning. Running them needs exact semantics, and the conformance vectors pin those down. This record is the list of choices made, so that another engine implementation can follow them.

## Decision Drivers

* The closed language must stay enumerable for the M3 checker.
* A screen protocol that stays typed (AGENTS.md rule 6).
* Enumeration safety by default ([CHARTER.md](../../CHARTER.md), principle 1).

## Considered Options

The tradeoffs were within each form, listed below, rather than between whole alternatives.

## Decision Outcome

* **`choose`**: an option is enabled unless its body is a `when` whose predicate is false. No enabled option fails with `no_method`. One enabled option runs without a picker. Two or more show `method_picker` with the enabled option ids, and the submission `{method}` must name one of them.
* **`retry n`**: `n` is total attempts (1 to 10). A handler failure unwinds to the innermost `retry` that has attempts left and reruns its body. The next screen shows `errors: [{code}]` and `attemptsLeft`. `(fail reason)` and `subject_mismatch` end the section and are never retried: a credential for someone else is not a typo.
* **`set name literal`** writes `vars.name`.
* **Predicates** are data: a bare path (truthiness), `and`, `or`, `not`, `exists`, and `eq`, `ne`, `has`, `gt`, `lt` taking `[path, literal]`. Paths start with a namespace (`request`, `client`, `hydra`, `tenant`, `user`, `vars`, `auth`). `auth` exposes `subject`, `amr` and `acr`.
* **Screen data**: a screen may carry server issued data (a WebAuthn challenge, a TOTP secret, the picker options). It is persisted with the waiting flow and handed to the handlers that consume the answer as `Submission.context`, never taken from the user. An enrollment secret survives a retry of the same screen.
* **Hosted answers**: `identifier` with `{passkey: true}` offers passkey autofill. A passkey submitted on it also answers the next `passkey` screen (not a registering one) the flow reaches before waiting again, and a `choose` takes the option that starts with that screen. The answer is used at most once.
* **Enumeration safety**: the shipped `passkey-totp` flow offers passkeys only through identifier autofill, which every account sees. It does not branch on enrollment (`user.has_*`) until a credential has verified. `identity.resolve` gives an unknown identifier the same `has_*` values as an account with only a password. Password checks always cost one Argon2 verification. Credential failures are always `invalid_credentials` or `invalid_code`.
* **Schema dispatch**: `$defs/node` dispatches on the form name with `if`/`then` instead of a `oneOf` over all forms. With `oneOf`, networknt validated every child against every form, and the work grew exponentially with nesting: `passkey-totp.json` ran out of memory at 2 GB. With dispatch, all conformance flows validate in about 160 MB. This confirms the risk recorded in [ADR 0001](0001-validate-ir-with-json-schema.md).

### Consequences

* Good, because each choice above is covered by a conformance vector and is language independent.
* Bad, because hosted answers are a special case in the engine (`ScreenType.hosted`). It is the smallest one we found that lets passkey autofill sign in without a second prompt.

### Confirmation

`cd server && ./gradlew build` runs the vectors in `conformance/vectors/` (retry, picker, no_method, passkey autofill, step up, consent), plus `IrCodecTest` and `FlowCompilerTest`.

### Changes since this decision

* [ADR 0024](0024-core-ir-as-a-narrow-waist.md) (IR format 1): an option is enabled by an explicit guard, `(opt id body {:guard predicate})`, not by its body being a `when`; a `choose`'s pick hook moved to its trailing options, `(choose (opt...) {:pick hook})`; the `hydra.*` namespace is `host.*`. The YAML surface is unchanged. Hosted answers are a property of the screen catalog (`ScreenSpec.hosted`), not of a `ScreenType` enum. The normative semantics are now [ir-spec.md](../roadmap/ir-spec.md).
