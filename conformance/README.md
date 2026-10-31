# Conformance suite

Language independent test vectors for the flow engine. Any engine implementation must pass all of them. What each rule means is in [ir-spec.md](../docs/roadmap/ir-spec.md), which names the vectors that pin it; this file only describes the format.

- `flows/*.json`: IR documents in format 1 (`schema/ir.schema.json`).
- `handlers.yaml`: the handler catalog contract. Production handlers must declare exactly this metadata (`HandlerContractTest`); the fakes here are built from it, one per entry, and only the scripted ones may be called.
- Hooks are scripted like handlers, so a flow's `hook` nodes may pin any hash; to the checker each stands for a stored hook with no capabilities. The JavaScript sandbox itself is tested in `server/faye-hooks`, not here.
- `vectors/*.yaml`: one scenario each, written by hand.
- `generated/<flow>/*.yaml`: one scenario per path the checker (`faye-check`) enumerates through each flow in `flows/` that passes it, in the same format. Generated, never edited: `FAYE_REGENERATE=1 ./gradlew :faye-check:test` rewrites them, and the build fails when they are stale. The generator finds inputs that take the path, runs the Java engine along it, and checks the engine ends the way the checker predicted. A path no input takes (the checker over-approximates) gets no vector; every success path of the shipped flows must get one.
- `compat/irN-stateM/`: frozen fixtures of each supported pair of IR format and state format, a version's canonical bytes (`version.json`) and a flow state saved waiting on it (`state.json`, binder `compat`). An engine must read them and finish the flow (`CompatTest`). They are written once and never regenerated; dropping a format deletes its directory in the change that does so.

## Vector format

```yaml
name: free text
ir: flows/default-login.json     # relative to this directory
subject: alice                   # subject bound by fake handlers that bind one (default: alice)
tenant: { mfa_required: true }   # optional tenant settings, read by flows as tenant.* (default: none required)
host:                            # optional HostRequest fields
  kind: login                    # or consent (runs the consent entry; default login)
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
    expect_identifier: bob@example.com
    expect_passkey: true         # passkey autofill offered
    expect_options: [totp, passkey]   # method_picker options
    expect_error: invalid_credentials # absent means the screen shows no error
    expect_attempts_left: 2
  - submit: { identifier: alice@example.com }
    race: lost                   # optional: the store refuses this save as if another submit had won
    # or race: { winner: { identifier: bob@example.com } }: another answer of this screen runs first and wins
    binder: someone-elses        # optional: answer with another browser's binder
  - expect_refused: conflict     # the submit before was refused with this code; required after any refusal
  - expect_commits: [totp.enroll:e1]   # every effect committed so far, in order
  - expect_accepted: 1           # how many accepts the host has received so far
  - publish: flows/other.json    # publish another version as the tenant's current one; the running flow stays on its own
  - expect_result: { succeed: true, subject: alice, amr: [pwd], acr: "faye:1fa" }
  # or, for consent: { succeed: true, subject: alice, grant_scope: [openid], user: { email: a@example.com } }
  #   (user, when given, is exactly what the consent hands the host about the person; claims are the host's)
  # or: { succeed: false, reason: invalid_credentials }
expect_trace:                    # the events a preview flow traced, in order; absent means none
  - start login
  - step identify
  - screen identifier
  - call identity.resolve ok
  - end succeed pwd faye:1fa
```

A trace event is written as its fields that are set, space separated: the event, its id (none for `start`, whose id is the version hash), its outcome, the amr it carries joined by `+`, and the acr. Events are `start <entry id>`, `step <id>`, `option <id> only|user|hook`, `screen <type>`, `call <handler> ok [amr]` or `call <handler> <error>`, `hook <id> ok|<failure>`, `retry <error>`, `commit <handler> <code>` (an effect whose commit failed), and `end succeed [amr acr]` or `end <reason>`. Only preview clients' flows are traced, and a run's events are traced only once the state it led to is saved.

Every scripted outcome must be consumed by the end of the vector. Every hook run must receive a `ctx` with exactly `request`, `client`, `host`, `tenant`, `user` and `vars` (plus `options` for the hook of a `choose`), never `auth`.
