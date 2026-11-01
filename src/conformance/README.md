# Conformance suite

Language independent test vectors for the flow engine. Any engine implementation must pass all of them. What each rule means is in [ir-spec.md](../../docs/ir-spec.md), which names the vectors that pin it; this file only describes the format.

- `flows/*.json`: IR documents in format 1, or format 2 for a flow with an `approve` entry (`src/schema/ir.schema.json`).
- `blocks.yaml`: the block contract (ADR 0030): the built in blocks a flow's steps may be, with their handler, screen, role and settings. faye-identity's `IdentityBlock` and the Admin UI's palette are each tested against it.
- `handlers.yaml`: the handler catalog contract. Production handlers must declare exactly this metadata (`HandlerContractTest`); the fakes here are built from it, one per entry, and only the scripted ones may be called.
- Hooks are scripted like handlers, so a flow's `hook` nodes may pin any hash; to the checker each stands for a stored hook with no capabilities. The JavaScript sandbox itself is tested in `src/faye-core` (`faye.hooks`), not here.
- `vectors/*.yaml`: one scenario each, written by hand.
- `generated/<flow>/*.yaml`: one scenario per path the checker (`faye-check`) enumerates through each flow in `flows/` that passes it, in the same format. Generated, never edited: `FAYE_REGENERATE=1 ./gradlew :faye-check:test` rewrites them, and the build fails when they are stale. The generator finds inputs that take the path, runs the Java engine along it, and checks the engine ends the way the checker predicted. A path no input takes (the checker over-approximates) gets no vector; every success path of the shipped flows must get one.
- `compat/irN-stateM/`: frozen fixtures of each supported pair of IR format and state format, a version's canonical bytes (`version.json`) and a flow state saved waiting on it (`state.json`, binder `compat`). An engine must read them and finish the flow (`CompatTest`). They are written once and never regenerated; dropping a format deletes its directory in the change that does so.

## Vector format

```yaml
name: free text
ir: flows/default-login.json     # relative to this directory
subject: alice                   # subject bound by fake handlers that bind one (default: alice)
tenant: { mfa_required: true }   # optional tenant settings, read by flows as tenant.* (default: none required);
                                 # remember_me: true offers "keep me signed in" on the identifier screen
                                 # flows: { authenticate: { lifetime_s: 600, idle_timeout_s: 300 } }: flow settings
                                 # by entry kind, in seconds (ADR 0035); either may be absent
host:                            # optional HostRequest fields
  kind: login                    # or consent (runs the consent entry), or approve (the approve entry; default login)
  client: { client_id: demo }    # what flows read as client.*; the engine decides nothing from it
  tenant: acme                   # the tenant whose flow runs, as the host decided it (default: the default tenant)
  preview: true                  # the host marks a preview client's request (default: false)
  params: { login_hint: alice@example.com, acr_values: [faye:2fa] }   # what flows read as host.*; only the
                                 # fields of the host contract (ir-spec.md, Host contract), any other key is an error
handlers:                        # scripted outcomes, consumed in call order
  identity.resolve: [{ ok: { user: { has_totp: true } } }]   # ok, merging fields into user.*
  password.verify: [{ fail: invalid_credentials }, ok]
  webauthn.assert: [{ ok: { amr: [hwk] } }]                  # ok, proving part of the handler's amr
  totp.verify: [{ ok: { subject: bob } }]                    # ok, binding another subject than the vector's
  totp.enroll: [{ ok: { effects: [e1] } }]                   # ok, with an effect committed as totp.enroll:e1
  webauthn.register: [{ ok: { effects: [{ id: e2, fails: invalid_credentials }] } }]   # an effect whose commit fails
issuers:                         # scripted issues per screen type, consumed in order; unscripted issues succeed
  otp_enroll: [fail, ok]         # fail: the issuer throws (an upstream that is down), so the step fails with issuer_error
actions:                         # scripted screen actions per screen type (ADR 0032), consumed in order; unscripted ones succeed
  code_sent: [{ refused: send_limited }, fail]   # refused: the issuer declined (a limit); fail: it threw (issuer_error)
hooks:                           # scripted runs per hook id, consumed in run order
  risk: [{ returns: { set: { risk: high } } }]               # the raw value the hook returned, judged by the engine
  route: [{ returns: { pick: otp }, expect_options: [password, otp] }]   # also checks ctx.options
  slow: [{ throws: exceeded its deadline }]                  # the runtime failed (error, timeout, memory)
  scored: [{ returns: {}, expect_request: { ip: 203.0.113.7 } }]   # also checks entries of ctx.request
preview:                         # what Previews gives a preview client; without draft its sign in is refused
  draft: flows/other.json        # the version a preview client runs in place of the current one
  request: { ip: 203.0.113.7 }   # overrides of request.* a preview flow sees
events:
  - expect_screen: identifier    # screen type; optional checks below
    expect_step: identify
    expect_path: /login          # where the Login UI shows it (ADR 0029)
    expect_identifier: bob@example.com
    expect_passkey: true         # passkey autofill offered
    expect_password: true        # the identifier screen asks for the password too
    expect_remember: true        # the identifier screen offers "keep me signed in" (ADR 0033)
    expect_channel: email        # code_sent: how the code went (ADR 0032)
    expect_to: a•••@example.com  # code_sent, phone_enroll: where it went, masked
    expect_resend: true          # code_sent, phone_enroll: another code may be sent (resendIn is present)
    expect_required: true        # passkey (register), phone_enroll: the person may not skip (ADR 0038)
    expect_qr_status: pending    # qr: where its QR code is, as the last poll read it
    expect_options: [totp, passkey]   # method_picker options
    expect_error: invalid_credentials # absent means the screen shows no error
    expect_attempts_left: 2
  - submit: { identifier: alice@example.com }
    race: lost                   # optional: the store refuses this save as if another submit had won
    # or race: { winner: { identifier: bob@example.com } }: another answer of this screen runs first and wins
    binder: someone-elses        # optional: answer with another browser's binder
  - act: resend                  # a screen action (ADR 0032); or act: { action: send, input: { phone: "+15551234821" } }
    nonce: not-this-screen       # optional: a nonce other than the screen's
  - expect_refused: conflict     # the submit or action before was refused with this code; required after any refusal
  - expect_commits: [totp.enroll:e1]   # every effect committed so far, in order
  - expect_accepted: 1           # how many accepts the host has received so far
  - wait: 600                    # this many seconds pass on the engine's clock (it moves only here)
  - publish: flows/other.json    # publish another version as the tenant's current one; the running flow stays on its own
  - expect_result: { succeed: true, subject: alice, amr: [pwd], acr: "faye:1fa" }
  #   (add remember: true when the person asked to stay signed in; absent means false)
  # or, for consent: { succeed: true, subject: alice, grant_scope: [openid], user: { email: a@example.com } }
  #   (user, when given, is exactly what the consent hands the host about the person; claims are the host's)
  # or: { succeed: false, reason: invalid_credentials }
  #   (a login that ran and failed has its redirect marked failed, so the Login UI shows /error; a refused
  #   consent's is not, nor a request refused before any flow ran: flow_unavailable, preview_unavailable; ADR 0031)
expect_trace:                    # the events a preview flow traced, in order; absent means none
  - start login
  - step identify
  - screen identifier
  - call identity.resolve ok
  - end succeed pwd faye:1fa
```

A trace event is written as its fields that are set, space separated: the event, its id (none for `start`, whose id is the version hash), its outcome, the amr it carries joined by `+`, and the acr. Events are `start <entry id>`, `step <id>`, `option <id> only|user|hook`, `screen <type>`, `call <handler> ok [amr]` or `call <handler> <error>`, `hook <id> ok|<failure>`, `retry <error>`, `commit <handler> <code>` (an effect whose commit failed), and `end succeed [amr acr]` or `end <reason>`. Only preview clients' flows are traced, and a run's events are traced only once the state it led to is saved.

Every scripted outcome must be consumed by the end of the vector. Every hook run must receive a `ctx` with exactly `request`, `client`, `host`, `tenant`, `user` and `vars` (plus `options` for the hook of a `choose`), never `auth`.
