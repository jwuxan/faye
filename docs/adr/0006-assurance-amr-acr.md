---
status: accepted
date: 2026-10-30
decision-makers: Josh Wu
---

# Computing acr, enforcing acr_values, and reusing sessions

## Context and Problem Statement

The Hydra integration plan says `acr` is `faye:1fa` for one factor and `faye:2fa` for "two distinct factor classes, or a user verified passkey", and that unsatisfied `acr_values` are rejected. The charter adds that a session may be reused only when it satisfies the assurance the client asked for. Implementing this means making several precise choices.

## Decision Drivers

* `amr`/`acr` reflect only what verified (D13, charter principle 1).
* Never issue weaker assurance than requested; reject instead.

## Considered Options

* Classes per amr value vs counting amr values.
* For `acr_values`, match any value vs require all vs meet the weakest.
* For a Hydra `skip`, reuse it always, never, or only if it meets `acr_values`.

## Decision Outcome

1. **Factor classes**: `pwd` is knowledge, `otp` and `hwk` are possession, `user` is inherence. Any other amr value is its own class. Two or more distinct classes give `faye:2fa`. A user verified passkey (`hwk`, `user`) is therefore 2fa, while `hwk` plus `otp` is 1fa. Amended by [ADR 0032](0032-delivered-one-time-codes-and-qr-sign-in.md): `sms` is possession, and `email` counts as a factor alone but never as a second, so `pwd` plus `email` is 1fa. Handlers may prove only part of their declared amr (`Outcome.Ok.amr`), e.g. a passkey without user verification proves `hwk` but not `user`.
2. **`acr_values`**: a login that reaches `succeed` must be at least as strong as the weakest requested value Faye knows. Unknown values are ignored. Otherwise the section fails with `acr_not_satisfied` and Hydra gets `access_denied`.
3. **`skip`** (Hydra remembers a session): Hydra tells us who, not how strongly they signed in. So a reused session counts as `faye:1fa`. If that meets `acr_values`, the login is accepted at once with an empty `amr`. Otherwise the flow runs as a step up, with the subject pre-bound, so a credential for anyone else ends in `subject_mismatch`.

### Consequences

* Good, because a client asking for `faye:2fa` never gets a token that claims less than what verified.
* Bad, because a session that was 2fa is treated as 1fa on reuse. Remembering the session's acr (for example in Hydra's context) can relax this later. Faye does not set `remember` yet, so Hydra does not skip today.

### Confirmation

Vectors `acr-not-satisfied`, `passkey-without-uv`, `step-up-enroll`, `passkey-autofill`; `EngineTest.rememberedSession*`; and `deploy/smoke.sh` steps 2 to 4 against real Hydra.
