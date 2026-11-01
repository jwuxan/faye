---
status: in progress
date: 2026-11-02
decision-makers: Josh Wu
---

# Required enrollment: adding a passkey or a phone, with or without "Not now"

## Context and Problem Statement

A flow can ask a signed in person to enroll something: a passkey (`add-passkey`, the `passkey` screen with `register`) or a phone for texted codes (`add-phone`, the `phone_enroll` screen; [ADR 0032](0032-delivered-one-time-codes-and-qr-sign-in.md)). Both screens have always taken `{skip: true}` ("Not now"), so a tenant could offer these enrollments but never require them. Some tenants want to require one: every account must have a phone before it goes on, say. Others want today's behavior. The setting has to be per step, set in the flow and from the console, and enforced by the server: the Login UI hiding a button is not enough (charter: safety checks live in Java).

Authenticator app enrollment (`enroll-totp`) is not affected. It is a credential, so it may stand as the second factor of a path, and it has never been skippable. A skippable credential would let a path that must show a second factor end without one.

## Decision Drivers

* The server decides: a declined enrollment the flow requires must be refused by the engine, not only hidden by the Login UI.
* No change to what the IR means. A new IR form or a format bump has to buy something a screen option cannot.
* Today's flows keep their behavior unchanged.

## Considered Options

1. A block setting `required`, lowered to a screen option of the same name on `passkey` (with `register`) and `phone_enroll`.
2. A new IR form, or a flag on `step`, marking a step as mandatory.
3. Separate blocks (`require-passkey`, `require-phone`) with screens of their own.
4. A tenant setting that makes every enrollment required.

## Decision Outcome

Chosen option: 1, a block setting `required` (default `false`) on `add-passkey` and `add-phone`. It is a screen option (ADR 0030's `option` target):

```yaml
add-phone:
  block: add-phone
  with: { required: true }   # lowers to ["screen", "phone_enroll", {"required": true}]
```

* **Engine.** `BuiltinScreen` takes the option on `passkey` (only with `register`) and `phone_enroll`, keeps it in the screen's data and renders `required: true`. Such a screen refuses `{skip: true}` as a malformed answer (`invalid_submission`). The step then ends only by enrolling, or by using up its attempts, which fails the sign in. Without the option, nothing changes.
* **IR.** No change. In the grammar a screen's options are opaque literals (`[screen, type, options?]`), and the screen catalog gives them meaning. No new form, no format bump.
* **Screen protocol.** `PasskeyScreen` and `PhoneEnrollScreen` gain an optional `required: true`. Absent means the person may skip, so a Login UI built before this record still shows "Not now" on a required screen, and the server refuses the skip.
* **Block contract.** `conformance/blocks.yaml` gives `add-passkey` and `add-phone` the setting `required: { default: false, target: option }`. A tenant's block defaults (`BlockSpec.withDefaults`) can turn it on for every step, and a flow's `with` still wins.
* **Login UI.** Hides "Not now" when the screen says `required`.
* **Admin UI.** The block inspector has a Required switch for both blocks. The palette carries the default.

Option 2 changes what the IR means, and needs a format bump and a checker rule for one bit only two screens read. Option 3 doubles two blocks and their screens for one boolean. Option 4 is too coarse: a tenant may require a phone on one path and offer a passkey on another. It stays possible through block defaults.

### Consequences

* Good: a tenant can require enrollment, and the server enforces it whatever the UI shows.
* Good: no IR change. Stored versions and continuations read as before.
* Neutral: a required enrollment with attempts that runs out fails the sign in. That is what requiring it means. The default stays skippable.
* Bad: the console's General settings page does not yet offer `required` as a tenant default. Only the flow (YAML or the block inspector) sets it.

### Confirmation

* `cd src && ./gradlew build`: the conformance vectors `add-phone-required`, `add-passkey-required` (a required screen renders `required` and refuses `{skip: true}`), and `add-phone-declined`, `step-up-enroll` (an unrequired one does not render it, and takes the skip). Also `BuiltinScreenTest.aRequiredEnrollmentCannotBeDeclined` (YAML `with: { required: true }` lowers to the screen option).
* `cd apps/server && ./gradlew build`: `BlocksTest` checks the blocks against `blocks.yaml` and that the setting names a screen option.
* `cd apps/web && pnpm test && pnpm e2e`: the palette matches `blocks.yaml`, and the Login UI's "adding a phone, required or not" tests.

Open gaps (why this is in progress): `deploy/smoke.sh` has not been run against a stack with a required enrollment, and General settings does not offer the tenant default.
