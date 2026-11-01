---
status: in progress
date: 2026-11-01
decision-makers: Josh Wu
---

# Blocks with fixed screens and handlers: a flow orchestrates, it does not assemble

## Context and Problem Statement

Before this record a step of the YAML surface was a bag of keys: `screen`, `use`, `retry`, `set`, `hook`, `alternative`, `steps` and `fail` could be mixed in any body, and the Admin UI's inspector offered every one of them on every block. An operator could pair the password screen with the TOTP handler, put a screen on a choice, run a hook as a step of its own, put the identifier anywhere, or ask for a password twice. The compiler and checker refused some of this, late and in IR terms; the rest compiled into flows nobody meant.

Operators should arrange steps, not build them. Which screen a credential shows, which handler verifies it and how many attempts it gets are properties of the credential, chosen by the people who review the handlers (charter principle 2), not choices each tenant makes again. How can the surface fix those, keep hooks around each step, and tell an author what to write when they reach for a key that is gone, without changing what the IR means?

## Decision Drivers

* Charter principle 6: tenants configure what happens and in what order, not how screens are built.
* Charter principle 2: only built in, reviewed handlers verify credentials; a flow arranges them.
* Charter principle 5: errors point at what the author wrote, and say what to write instead.
* ADR 0024: the Core IR is a narrow waist; new capability arrives through catalogs, and a change to what the IR means bumps its format.
* ADR 0025: the core (faye-ir, faye-dsl, faye-engine, faye-check) names no screen and no handler; the caller hands the compiler what its catalogs say (`BoundaryTest`).
* D7: identity first by default, now with the combined form as an explicit setting (below).

## Considered Options

1. **A. Blocks as a catalog SPI over the IR.** A `BlockSpec` (faye-dsl) fixes a block's screen, handler and role and declares the settings a flow may change, with defaults. Implementations live beside their handlers (faye-identity `IdentityBlock`); the app hands them to the compiler in `FlowCompiler.Options`, as it hands it consent's screen and handler. A step says `block: password` and, at most, `with: { attempts: 5 }`; the compiler lowers it to the `(screen ...) (call ...)` the IR already has.
2. **B. A `(block id opts)` IR form** that the engine resolves through the SPI at run time.
3. **C. Keep `use: handler`**, imply and check the screen, and only restrict which keys each handler's step takes.

## Decision Outcome

Chosen: **A.** The flow names blocks and orders them; the block brings its screen, handler and defaults; the IR, the engine, the checker and the conformance suite are unchanged.

```yaml
login:
  steps:
    identify:
      block: identifier             # always the first step, and only there
      with: { password: true }      # identifier and password on one screen; default false
      after: risk                   # hooks run before or after a block, never as a step
    second-factor:
      if: { eq: [vars.risk, high] }
      alternative:                  # two options or more: credential blocks or a fail
        totp: { if: user.has_totp, block: totp }
        enroll: { if: { not: user.has_totp }, block: enroll-totp }
    grant: { if: { not: client.first_party }, block: consent }
```

* **Kinds.** Every body is one kind: a block (`block`), a choice (`alternative`, `pick`), a group (`steps`), `set` or `fail`, and takes only that kind's keys, plus `if`, `before` and `after` (no `after` on a fail, which nothing follows; no `if` on the identifier, which always runs). Another key is refused with the keys that kind takes.
* **Places.** A block's role (`BlockSpec.Role`) decides where it goes: the identifier is the first step of the sign in, always, and only there; a choice has two options or more, each one credential or action block, or a fail; consent is a step of its own, never in a group or a choice.
* **Settings.** A block declares its settings with defaults (`IdentityBlock`: `attempts` 3 for password, TOTP and enrollment, 1 for a passkey; the identifier's `passkey` and `password`, off). `with` may set only those, with the declared type. Attempts above one lower to `(retry n ...)` around the block's own parts, inside its hooks, so a `before` hook runs once, not on every attempt. A block's screen is its own; its fixed options (`register: true` for adding a passkey) are always set. A tenant may replace a block's defaults (`faye.tenants.<id>.blocks`, or General settings in the Admin UI: `{ password: { attempts: 5 } }`), checked when saved; they apply when a flow is next compiled, so a published version keeps what it was compiled with, and a flow's `with` still wins. The identifier's `password` is not a default: which blocks may follow the identifier is the flow's to say.
* **Contract.** `conformance/blocks.yaml` lists the built in blocks, as `conformance/handlers.yaml` lists the handlers: `BlocksTest` holds `IdentityBlock` to it, and the console's palette test holds `PALETTE` to it, so neither drifts.
* **The identifier's password view.** With `password: true` the identifier shows the password field on its own screen and lowers to `(screen identifier {:password true}) (call identity.resolve) (call password.verify)`: one answer carries both (`IdentifierPasswordPayload`, schema/screen.schema.json), and both calls read it. It then takes no other option (passkey autofill would answer without the password), and a Password block anywhere in the flow is refused. Unknown accounts still resolve like known ones with a password (identity.resolve), so the combined form reveals no more than identity first; identity first stays the default (D7).
* **Moved keys.** `use`, `screen`, `retry` and a step that is only `hook` are refused with what to write instead (`write 'block: password'`, `write 'with: { attempts: 5 }'`, `write 'before: risk' on the block it runs before, or 'after: risk' on the one it follows`). The Admin UI opens such YAML converted (`fromYaml`), as it opened the consent section of ADR 0026: a handler becomes its block, screen options and attempts become settings, a hook step becomes the `after` hook of the block before it or the `before` hook of the block after it.
* **Console.** The inspector shows only the selected kind's fields and never a screen or handler; the palette, drag and drop, moves and deletes offer a block only where the compiler takes it (the Identifier first, credentials before Consent, options of one credential), and the palette says why not otherwise (`misfit`, `misplaced` in `web/apps/admin/lib/flow.ts`). Old YAML is converted only where its meaning survives (a hook step never moves under a neighbour's condition, after a fail or out of a choice; a screen that is not its handler's block's is refused, not dropped), and only when the operator asks ("Upgrade to blocks"). The server still decides (D10): the console only avoids offering what will be refused.

### Consequences

* Good, because a step cannot pair a screen with the wrong handler, ask for a password twice, or show a screen on a choice: the shapes are gone, not checked after the fact.
* Good, because the IR does not change: no format bump, the shipped flows lower to the same goldens (`FlowCompilerTest.tenantFlowLowersToItsGolden`, unchanged `.ir` files), and the conformance suite and the checker are untouched apart from one new screen option.
* Good, because the compiler still names no handler or screen (`BoundaryTest`); a new credential module adds its blocks beside its handlers.
* Bad, because every YAML flow written before this record must be rewritten. The compiler says how at each key, and the console converts on open; stored versions keep running, since they are IR. ADR 0026's and ADR 0029's examples show the surface before this record.
* Bad, because the block table exists twice in tests (faye-dsl `FlowCompilerTest`, faye-check `Fixtures`), as the consent sets already did, and once more as the console's palette; the palette and `IdentityBlock` are both tested against `conformance/blocks.yaml`, the test copies are not.
* Neutral, because the checker de-duplicates findings by kind and position, so a block whose screen and call are both refused for the same reason is reported once, at the block.

### Confirmation

* `cd server && ./gradlew build`: `FlowCompilerTest` (diagnostics `blocks.yaml`, `identifier.yaml`, `identifier-password.yaml`, `keysThatTookABlockApartSayWhatToWrite`, the goldens), `BlocksTest` (each `IdentityBlock` names a registered handler and screen, options the screen takes, and a role its handler's metadata agrees with), `BoundaryTest`, and the conformance vectors `identifier-password` and `identifier-password-retry`, with their generated paths.
* `cd web && pnpm test`: `flow.test.ts` builds the shipped flows from palette blocks and inspector edits, opens pre-0030 YAML converted, pins the placement rules and the tenant defaults, and holds the palette to `conformance/blocks.yaml`.
* `MilestoneFiveTest.blockDefaultsComeFromTheTenantAndTheFlowStillWins`: tenant defaults are refused when malformed and change the next compile.
* `deploy/smoke.sh` against a fresh stack.

## Pros and Cons of the Options

### A. Catalog SPI over the IR

* Good, because it is the smallest change that removes the free choices: the surface and the console change, the waist does not.
* Good, because it follows the pattern ADR 0025 set: the caller's catalogs tell the compiler what it needs.
* Bad, because the engine still runs `(screen ...)` and `(call ...)` and does not know blocks; a run time that reasons in blocks (traces, per block configuration at run time) would need B.

### B. A `(block id opts)` IR form

* Good, because the engine and traces would speak in blocks, and a block's behavior could change without recompiling flows.
* Bad, because it changes what the IR means: a format bump, a new checker path model, new conformance vectors and compat fixtures, and every engine implementation must resolve blocks (ADR 0024).
* Bad, because a block's behavior changing under a published version would break D5 (a flow runs the version it started on).

### C. Keep `use: handler`

* Good, because it is the smallest diff to existing flows.
* Bad, because the author still writes handler names and the screen is still a separate idea; the "which screen goes with which handler" choice stays visible.

## More Information

### DX review

Five review rounds, each on the state the previous one left, each closed before the next began.

| Round | Scope | Found | Resolved |
|---|---|---|---|
| 1 | Compiler diagnostics on the first typed surface (`use` with implied screens) | An empty body no longer said what it needs; a misplaced block reported twice | The first restored; the second kept, each message being actionable |
| 2 | Diagnostics after the move to `block:` and `with:` | Path errors pointed at the block, not the `path:` written; one block refused twice for the same reason | Path findings land on the path; one finding per block (fixtures `blocks.yaml`, `grammar.yaml`, `hooks.yaml`, `keysThatTookABlockApartSayWhatToWrite`) |
| 3 | Thirteen realistic author mistakes compiled one by one | A stale `use:` hint for an empty flow; a leaked `hook-id` placeholder; `retry: 3` told to become a setting it already is | All three (fixtures `moved.yaml`, `no-steps.yaml`) |
| 4 | Independent review of the console (12 findings) | Credentials allowed after Consent; old YAML converted with changed meaning (hook steps under conditions, after a fail, out of a choice; dropped `retry`, `screen`); identifier view leaving attempts; keys only fixable in YAML; delete and moves unchecked; drop lines for refused drops | All 12: the consent split in `misfit`/`misplaced`, migration refuses what it cannot carry, "Upgrade to blocks", the stray key panel, guarded delete, `removeEntry`, exact drop targets (`flow.test.ts`) |
| 5 | Independent review of the final state: tenant defaults, server findings in the block view, the upgrade banner, free form left, the contract (10 findings) | A restart published a new live version under console saved defaults; a tenant autofill default broke the password view; set plus use upgraded into refused YAML; no revalidation after saving defaults; findings misplaced on old YAML; more keys only fixable in YAML; a blur rewrote `with`; the old consent section missed by the banner; the palette ignored the tenant's allowlists; settings form details | All 10: boot compiles with configured defaults only (`SqliteRestartTest.publishedVersionSurvivesARestart`, which fails without the fix), option defaults yield to the password view (`tenantDefaultsReplaceTheBlocksAndTheFlowWins`), the rest in the console with `flow.test.ts` cases; the palette test now compares titles too |

Each round's fixes were checked before the next round: by the tests named above, and for round 5 by a separate verifier that re-probed all ten findings against the final tree and found each fixed with no regression (`pnpm test`: 27 admin, 3 login; `SqliteRestartTest`, `FlowCompilerTest`). `deploy/smoke.sh` then passed all 13 checks against a fresh stack built from the final tree. Two edges the verifier noted, both still refused by the server rather than offered wrongly in silence: an identifier in its password view is not greyed out when General settings disallow `password.verify`; and the settings form's unsaved changes check compares JSON text, so block defaults edited into another key order may show as a change.

No gaps remain open; the record waits for a human to accept it. Closed during the change: tenant defaults for block settings, the block contract the console's palette is tested against, per position drop targets, and a `deploy/smoke.sh` run against a fresh stack (all 13 checks pass).
