---
status: proposed
date: 2026-11-01
decision-makers: Josh Wu
---

# Delivered one time codes (email, SMS) beside TOTP, and QR code sign in, as blocks

## Context and Problem Statement

The only one time code Faye knows is TOTP from an authenticator app: the `totp` and `enroll-totp` blocks over `totp.verify` and `totp.enroll` ([ADR 0030](0030-blocks-with-fixed-screens-and-handlers.md), `conformance/blocks.yaml`). Tenants also ask for:

1. **Codes Faye delivers**: to the account's email address or to its phone by SMS, for people who have no authenticator app.
2. **A per person switch**: a flow must be able to tell whether this person turned phone codes on, and send them by email (or TOTP) when not.
3. **A choice in the flow editor**: the operator decides, per step, whether the second factor is TOTP, an emailed code or a texted code, or offers several, without writing a handler or a screen.
4. **QR code sign in**: a device that holds no credential and runs no app of ours (a desktop browser, a TV, a kiosk) shows a QR code; the person scans it with their phone, proves who they are there in the phone's browser, and the first device is signed in. It must be a block like any other, composable anywhere a credential goes.

How do these fit the engine without breaking the narrow waist ([ADR 0024](0024-core-ir-as-a-narrow-waist.md)), the closed extension rule (charter principle 2), the issuer boundary ([ADR 0025](0025-host-neutral-boundaries-for-embedding.md)) or assurance computed from what verified (D13, [ADR 0006](0006-assurance-amr-acr.md))?

## Decision Drivers

* Charter principle 1: issued material (a code, a QR token) is never readable by predicates or hooks; enumeration resistance stays the default; `amr` is computed, never configured.
* Charter principle 2: only built in, reviewed handlers verify; a block brings its own screen and handler (ADR 0030).
* Charter principle 3 and non-goal 2: Faye sends no tokens and owns no sessions. QR sign in must not become a session Faye keeps on the phone.
* Charter principle 4: no flow configuration is needed for a working tenant; channels that need a provider are off until one is configured, and a flow that names one fails at publish, not at sign in.
* Charter principle 5: common flows ("TOTP if they have it, else a text if their phone is on, else an email") are short to write.
* ADR 0024: new capability arrives through catalogs and compiler lowering, not forms; a change to what the IR means bumps the format.
* ADR 0025: the module that verifies a credential issues its material (`CredentialIssuer`); issued data lives only in the waiting screen's data and reaches the handler only as `Submission.context()`.

## Considered Options

1. **How the editor chooses the kind of code**
   * A. One `otp` block with a setting `with: { via: totp | email | sms }`.
   * B. One block per kind (`totp`, `email-code`, `sms-code`), grouped in the console as "One time code" with a kind switch.
2. **Who generates and sends a delivered code**
   * A. A handler `otp.send` called as a step before the code screen, its code kept in `vars`.
   * B. The block's `CredentialIssuer` generates the code when the screen is prepared and hands it to a `CodeSender` (the delivery SPI); the engine stores only the code's MAC.
   * C. A hook generates and sends the code through egress.
3. **Resending a code, and polling a QR code**
   * A. Model each as a failed attempt inside the block's `retry`.
   * B. Screen actions: a submission that names an action the screen declares, answered by its issuer, never by the handler, consuming no attempt.
   * C. A separate status API per module, outside the flow.
4. **How the phone side of QR sign in proves who it is**
   * A. A native app with its own session approves.
   * B. The phone's browser runs a flow of a new entry kind, `approve`, with the tenant's checks; its last block approves the waiting device.
   * C. The phone's browser runs the tenant's ordinary `authenticate` entry under a second, internal host, and the confirmation is a step guarded by `host.context`.
   * D. Rely on passkey hybrid transport (CTAP 2.2: the browser's own QR code) and add nothing.

## Decision Outcome

Chosen: **1B, 2B, 3B, 4B** (with 4D kept and recommended for passkey users).

```yaml
login:
  steps:
    identify: { block: identifier }
    credential: { block: password }
    second-factor:
      alternative:                    # the enabled ones are offered; one enabled runs at once
        app:   { if: user.has_totp,  block: totp }
        phone: { if: user.has_phone, block: sms-code }      # the person's switch, below
        email: { if: user.email_verified, block: email-code, with: { attempts: 5 } }
    grant: { if: { not: client.first_party }, block: consent }

# a device with nothing on it: show a QR code, or fall back to a password
# login:
#   steps:
#     identify: { block: identifier, with: { passkey: true } }
#     sign-in:
#       alternative:
#         qr:       { block: qr }       # binds whoever approved on their phone
#         password: { block: password }

approve:                              # runs in the phone's browser after the scan
  steps:
    identify: { block: identifier, with: { passkey: true } }
    credential:
      alternative:
        passkey:  { if: user.has_passkey, block: passkey }
        password: { block: password }
    confirm: { block: qr-approve }    # shows the other device and the matching number
```

### Blocks, handlers and screens (1B)

The new blocks, as they will appear in `conformance/blocks.yaml` and `conformance/handlers.yaml`:

| Block | Handler | Screen | Role | amr | Effect | Notes |
|---|---|---|---|---|---|---|
| `email-code` | `email_code.verify` | `code_sent` (`channel: email`, fixed) | credential | `[email]` | none | to the account's verified address |
| `sms-code` | `sms_code.verify` | `code_sent` (`channel: sms`, fixed) | credential | `[sms]` | none | to the account's verified phone, only when the person turned phone codes on |
| `add-phone` | `phone.enroll` | `phone_enroll` | action | `[]` | write | `requires_subject`; adds and verifies a number, turns phone codes on |
| `qr` | `qr.redeem` | `qr` | credential | the approver's, see Assurance | guard | binds the approving subject |
| `qr-approve` | `qr.approve` | `qr_approve` | approve (a new `BlockSpec.Role`) | `[]` | write | only in an `approve` entry, last, after a credential |

* Each block fixes its handler and screen, as ADR 0030 requires; a setting never swaps a handler, which is why 1A was rejected. Settings are `attempts` only (default 3 for the codes, 1 for `qr`). Code length (6 digits), lifetime (5 minutes), resend cooldown (30 s) and sends per step (3) are the module's, not the tenant's: they are the recommended values (charter principle 4) and the place a tenant would weaken a default without anyone seeing.
* `totp` and `enroll-totp` are unchanged.
* The console shows `totp`, `email-code` and `sms-code` as one palette entry, **One time code**, whose inspector has a **Kind** select (Authenticator app, Email, Text message). Changing the kind swaps the block id and keeps `attempts` and the hooks; it is an edit to the YAML, so the server still decides (D10). A **Code by** preset inserts the alternative above (the app if enrolled, the phone if its switch is on, email if verified), each option with its guard, so the common case is one click; the person picks among those enabled (`method_picker`), or a `pick` hook picks for them. `qr` and `qr-approve` are ordinary palette blocks; `qr-approve` is offered only in the Approve section.

### The person's switch: `user.*` and the account

* The account (`faye_user`, Flyway `V4`) gains `phone` (E.164, `^\+[1-9][0-9]{6,14}$`; no number parsing library, the Login UI's form asks for the country code), `phone_verified` and `phone_codes` (the person's switch, default off).
* `identity.resolve` adds `user.has_phone`: true only when the account has a verified phone **and** phone codes are on. It is false for an unknown account, as `has_totp` is. The number itself never enters `user.*`: flows and hooks branch on the switch, and only the issuer sees the number, presenting it masked (`+1 ••• ••• 4821`).
* `user.email_verified` already exists and guards `email-code`.
* `add-phone` sets all three in one write, after the code sent to the new number verified, so the switch is never on for a number nobody proved. Turning phone codes off is account settings, which Faye does not own (charter non-goal 3); the user store accepts the change from whoever owns the account (for the demo, `deploy/directory/seed.mjs`).
* For the tenant, a channel is on only when its delivery is configured (`faye.tenants.<id>.delivery.email` / `.sms`, below). The app hands the compiler only the blocks its tenant can run, so a flow naming `sms-code` for a tenant without SMS delivery is refused at publish with what to configure, and the console's palette greys it out with the same reason.

### Issuing and delivering a code (2B)

* `email-code` and `sms-code` share one screen type, `code_sent`, whose issuer (`DeliveredCodeIssuer`, faye-identity) on `issue`:
  1. picks the destination from the account: the verified email, or the verified phone with phone codes on. It never takes a destination from the submission, the flow or a hook.
  2. generates 6 digits from `SecureRandom`, stores `HMAC-SHA256(server key, flowId ‖ code)` with its expiry and send count in the waiting screen's data, and puts the code in the outbox (`faye_outbox`) for that channel.
  3. presents the channel, the masked destination, `resendAfter` and `expiresIn`. The code is never presented.
* For an unknown account, or one whose destination is gone, the issuer stores a MAC of nothing that can match, writes an outbox row marked `discard`, and presents the masked identifier as typed. The screen and its timing are the same as for a real account; every code is then `invalid_code` (enumeration, charter principle 1). This mirrors `has_password` being true for unknown accounts.
* `email_code.verify` and `sms_code.verify` compare the MAC in constant time, refuse an expired code (`code_expired`, retryable, so the person can resend), and on success bind the subject. They need no guard: the code exists only in this flow's state, and the revision compare and set already makes two answers with the right code accept once.
* **Delivery** is a `CodeSender` SPI in faye-identity (`send(channel, destination, code, locale, tenant)`), drained from the outbox by one worker with a per send deadline, so a slow provider never slows a screen and the request path does the same local write for every account. Two senders ship:
  * `outbox`: writes the message under `/var/lib/faye/demo/outbox/`, read with `docker exec`, as the demo's passwords are. Demo and tests only; refused at startup outside `FAYE_DEMO=true` or tests.
  * `webhook`: POSTs `{channel, to, code, locale, tenant}` as JSON to the URL the tenant configures, signed with HMAC-SHA256 over the body and a timestamp (`Faye-Signature`), no redirects. The operator's endpoint hands it to their mail or SMS provider. Faye ships no provider SDK and no SMTP client; the JDK's `HttpClient` is enough, so no dependency is added.
* **Abuse limits**, enforced in the issuer on every send, all fixed: one send per 30 s per step; 3 sends per step; per destination, 5 sends per hour and 10 per day; per tenant and channel, a ceiling the operator sets (`delivery.sms.per_hour`, default 100), because SMS pumping fraud bills the tenant. A send over a limit is `send_limited`, shown as a screen error, not a server error. A `before` hook (a risk score) runs before the block's screen is prepared, so it can `fail` the step before anything is sent.
* 2A was rejected because the code would sit in `vars`, readable by every hook and predicate, and every author would plumb a send step before each code screen. 2C was rejected because a hook would then hold the secret and choose the destination; hooks may only set, fail or pick (charter principle 2).

### Screen actions (3B)

Resending a code, sending the first code to a number being added, and asking whether a QR code was approved are not answers: none of them should run the handler or spend an attempt.

* A `ScreenSpec` declares the actions it takes (`code_sent`: `resend`; `phone_enroll`: `send`; `qr`: `poll`). A submission with `{"action": "<name>", "input": {...}}` (screen protocol `SubmitRequest`, additive) calls `CredentialIssuer.act(env, opts, issued, action, input)`, which returns the screen's new issued data; the engine stores it under the same compare and set as any answer and answers with the re-rendered screen. The handler does not run, no attempt is spent, the flow does not move.
* An action a screen does not declare is refused (`unknown_action`), as is any action on a screen without an issuer. The issuer applies the limits above, so an action cannot be used to send more often than resending allows.
* `present` stays local and deterministic over the issued data: a QR code's status is read by `act("poll")` into the issued data, and `present` shows what was read.
* 3A was rejected because a resend would spend an attempt and the retry would rerun the screen's issue, so `attempts` would mean two things. 3C was rejected because every module would add its own route beside the flow, outside the binder and the compare and set.

### QR code sign in (4B)

**The waiting device.** The `qr` block's issuer creates a grant (`faye_qr_grant`: token hash, flow id, tenant, client, preview flag, a two digit matching number, the requesting device's summary (browser family, approximate place from the request's IP when the operator configures a lookup, else none), status `pending`, expiry 2 minutes) and presents the URL to encode, `https://<login>/approve/<token>` (a 128 bit token, base64url), the matching number and `expiresIn`. The Login UI draws the QR code with uqr ([ADR 0004](0004-identity-dependencies.md)) and sends `poll` every 2 seconds; when a poll reads `approved`, it submits the screen, and `qr.redeem` consumes the grant atomically (a guard: approved, unexpired, for this flow, never consumed), binds the approving subject and proves the approver's `amr` (Assurance). An expired grant is `qr_expired`, retryable: the retry issues a new code. A grant is never usable by another flow, because it names its flow and the redeem runs only with that flow's binder.

**The phone.** Scanning opens the Login UI at `/approve/<token>` (a new path prefix in `screen.schema.json`). It starts a flow of the new `HostKind.APPROVE` through an internal host in the new `faye-qr` module (`QrApprovalHost`), which loads the grant and fills `HostParams` from it: the tenant, the client's name and the preview flag of the waiting device's flow, never anything the phone sends. `EntrySelector` maps it to the flow's `approve` entry; a flow without one cannot offer `qr` (checker). The host completes with `Handoff` and the Login UI shows "Approved, return to your other device"; nothing is redirected and no session is created on the phone.

**The `approve` entry kind.** `(entry approve approve body)`. It sees nothing of the waiting device but the client and the device summary. The checker holds it to the authenticate rules (a credential that binds the subject verifies on every success path, after an identifier) and adds: `qr.approve` runs exactly once on every success path, last, and nowhere else; no `consent` screen or handler; no `qr` block (a QR code cannot approve a QR code). The `qr_approve` screen shows the client, the device summary and three numbers, one of them the waiting device's; `qr.approve` succeeds only for the right number, and stages the approval (subject, the entry's computed `amr`) as a write applied when the entry succeeds. A wrong number is terminal and marks the grant `refused`, so the waiting device stops at once.

The rest of the approve design:

* The YAML surface gains a top level `approve:` section, compiled to the `approve` entry, and the console a third section beside Sign in and Consent (ADR 0026's split).
* Adding an entry kind changes what the IR means, so this is **IR format 2**: format 1 plus the `approve` kind. The publisher writes format 1 for any flow without an `approve` entry, so no existing version or hash changes; readers accept both. `ir-spec.md` and `ir.schema.json` gain the kind, and compat fixtures pin that a format 1 reader refuses a format 2 flow.
* 4A was rejected for now: a native app's session belongs to the relying party, and accepting its word means Faye trusts a token it did not see verified (charter non-goal 2). An upstream approver can be added later as a reviewed handler module writing the same grant.
* 4C was rejected because the confirmation would be an ordinary step under a `host.context` guard: a flow could run the tenant's login on the phone and forget it, and the approve policy could not differ from the login's (a tenant may want a passkey on the phone even when the desktop sign in takes a password).
* 4D stays the better answer for people with a passkey on their phone: the browser's own cross device QR code is phishing resistant, which 4B is not. The `passkey` block already gets it from the platform, without anything new in Faye, and the console's help for `qr` says so.

### Assurance (amends ADR 0006 item 1)

| amr | Proved by | Class for `acr` |
|---|---|---|
| `sms` (RFC 8176) | `sms_code.verify`, `phone.enroll` proves none | possession |
| `email` (not in RFC 8176) | `email_code.verify` | mailbox: counts as one factor alone, never as a second |
| whatever the approver proved | `qr.redeem` | the classes of those values |

* NIST SP 800-63B forbids email as an out of band authenticator and restricts SMS (link under More Information). So `pwd` plus `email` is `faye:1fa`: a login whose only possession proof is a mailbox never reaches `faye:2fa`. `pwd` plus `sms` is `faye:2fa`, as `pwd` plus `otp` is. This adds one rule to ADR 0006 item 1 (a value that counts toward 1fa only) for a value no handler emits yet, so no existing outcome changes; the human decision maker should confirm that this amends rather than supersedes 0006.
* `qr.redeem` declares the union of the amr values an approve entry can prove and proves exactly the subset the approval recorded (`Outcome.Ok.amr`), so a phone that verified a password gives the waiting device `pwd` and `faye:1fa`, never more. The waiting device's `acr_values` apply as usual.

### What stays as it is

* The IR forms, the `authenticate` and `consent` entries, `totp`, `enroll-totp`, the hook result table and `HostParams` fields.
* The engine names no new screen or handler: the new names live in faye-screens, faye-identity and faye-qr and are registered by the app (`BoundaryTest` gains them).

### Consequences

* Good, because the three kinds of code are one decision in the editor, each a reviewed block, and the switch is a predicate (`user.has_phone`) like `user.has_totp`.
* Good, because codes, destinations and QR tokens stay in the waiting screen's data and the outbox, never in `Env`, and hooks can still stop a send before it happens.
* Good, because a device with nothing on it can sign in through a phone without an app and without Faye keeping a session.
* Bad, because QR sign in can be relayed: an attacker shows a victim the QR code of the attacker's own waiting device (QRLjacking), and the victim approves. The matching number, the device summary, the 2 minute expiry and the approve entry's own credential raise the cost; they do not remove it. It is never phishing resistant, and the console says so. A tenant that needs phishing resistance uses passkeys (4D).
* Bad, because delivered codes depend on an operator's endpoint, and SMS costs money per send; the limits bound the cost but an operator must still watch it.
* Bad, because IR format 2 is the first format bump: every engine implementation must learn the `approve` kind to run QR flows.
* Neutral: `email` is not an RFC 8176 value. A relying party that only knows RFC 8176 sees an unknown value; `acr` still says what it is worth.

### Confirmation

Planned; none of this exists yet.

* `cd server && ./gradlew build`:
  * conformance vectors `email-code`, `email-code-expired-then-resend`, `email-code-unknown-account` (same screen, never verifies), `sms-code-switch-off` (with the switch off only email is enabled, so it runs without a picker), `code-resend-limited`, `code-action-spends-no-attempt`, `add-phone-then-sms`, `qr-approved`, `qr-expired-then-retries`, `qr-wrong-number`, `qr-approver-pwd-is-1fa`, and `email-after-password-is-1fa`, with their generated paths;
  * `CheckerTest`: an `approve` entry without `qr-approve` last, a `qr` block in a flow without an `approve` entry, `qr-approve` outside an approve entry, and a tenant without SMS delivery naming `sms-code` are each refused;
  * `BlocksTest` and `HandlerContractTest` against the new rows of `conformance/blocks.yaml` and `conformance/handlers.yaml`; `DeliveredCodeIssuerTest` (the code never presented, MAC only, limits, unknown accounts); `EmbeddingTest` (an action reaches only the issuer); `BoundaryTest`; IR format compat fixtures;
  * a fuzz harness for `SubmitRequest` actions and one for the QR grant store (ADR 0007).
* `cd web && pnpm generate && pnpm typecheck && pnpm lint && pnpm test`: the kind switch keeps attempts and hooks, the **Code by** preset, the Approve section, and the palette held to `conformance/blocks.yaml`.
* `deploy/smoke.sh` on a fresh stack: a sign in with an emailed code read from the demo outbox, and a QR sign in approved by a second browser context.

## Pros and Cons of the Options

### 1A. One `otp` block with `via`

* Good, because the editor shows one block.
* Bad, because a setting would choose the handler and the screen, which ADR 0030 removed on purpose; the block contract would need a new setting target that swaps both.

### 1B. One block per kind, one palette entry

* Good, because each block keeps one handler, one screen and one amr, and the console still offers one choice.
* Bad, because three rows in `blocks.yaml` and in the palette test.

### 2A. `otp.send` handler and `vars`

* Good, because no new SPI.
* Bad, because the code is readable by hooks and predicates, and the destination could come from the flow.

### 2B. Issuer plus `CodeSender`

* Good, because it is ADR 0025's boundary unchanged: the verifying module issues, the engine stores, the handler reads the submission's context.
* Bad, because an outbox table and a worker are new moving parts.

### 2C. Hook sends

* Bad, because a hook would hold a credential.

### 3A. Actions as failed attempts

* Bad, because it spends attempts and reruns `issue` on every resend.

### 3B. Screen actions

* Good, because one mechanism serves resend, the first send of `add-phone` and the QR poll, under the binder and the compare and set.
* Bad, because the screen protocol and the engine's submit path grow a second kind of submission, which every embedder's UI must send correctly.

### 3C. Per module status API

* Bad, because it bypasses the binder and the flow's revision.

### 4A. Native app approves

* Good, because no credential prompt on the phone.
* Bad, because Faye would trust a session it did not see verified; out of scope until an upstream approver module is reviewed.

### 4B. `approve` entry

* Good, because the phone side is a flow the checker proves, with its own policy, and nothing is kept on the phone.
* Bad, because it bumps the IR format and asks the person to sign in on the phone.

### 4C. Ordinary login under an internal host

* Good, because no IR change.
* Bad, because the confirmation is optional by construction and the policy cannot differ from the login's.

### 4D. Passkey hybrid only

* Good, because phishing resistant and already supported by browsers.
* Bad, because it needs a passkey on the phone; a person with only a password cannot use it.

## More Information

### Open questions for the decision maker

1. **Charter, success criterion 3** says no sign in succeeds "unless a built in handler verified or enrolled a credential for that subject, in this flow or in the session it reuses". In QR sign in the credential verifies in the phone's `approve` flow, and `qr.redeem` verifies a single use grant that only that flow can create. This record reads the grant as the credential `qr.redeem` verifies; if that reading is wrong, QR sign in needs a charter change first (charter, Changing this charter), and nothing here should be built until it is settled.
2. Whether the `email` rule amends ADR 0006 or needs a record that supersedes it.

### Unverified claims, and how to verify them

* NIST SP 800-63B forbids email and restricts SMS as out of band authenticators: rev 3 section 5.1.3.1 (https://pages.nist.gov/800-63-3/sp800-63b.html). Check the same rule in the final 800-63B-4 before implementation.
* `sms` is an RFC 8176 amr value and `email` is not: https://www.rfc-editor.org/rfc/rfc8176#section-2.
* QRLjacking: OWASP, https://owasp.org/www-community/attacks/Qrljacking. Number matching mitigates approving the wrong prompt, not a relayed one; verify against the attack write up when the screen copy is drafted.
* Passkey hybrid transport (cross device QR code): CTAP 2.2, https://fidoalliance.org/specs/fido-v2.2-rd-20230321/fido-client-to-authenticator-protocol-v2.2-rd-20230321.html. Check browser support when the console help is written.
* The limits (30 s cooldown, 3 per step, 5 per hour and 10 per day per destination, 100 per hour per tenant) are starting values, not measured; revisit them with the first tenant's send volumes.
