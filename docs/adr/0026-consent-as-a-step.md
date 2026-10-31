---
status: in progress
date: 2026-10-31
decision-makers: Josh Wu
---

# Consent as a step of the sign in

## Context and Problem Statement

Before this record a flow had two top level sections in YAML, `login:` and `consent:`, and the Admin UI showed them as two categories with their own headers, an "Add consent section" button and a remove button. Consent is not a category of the same rank as signing in: it is one more thing a person does on the way into an application, after proving who they are and before the authorization server issues tokens. Authors think of it as the last step ("identify, password, second factor, consent"), and the charter lists it among the common flows that must be "short to write and easy to read" (principle 5).

The IR has a reason to keep two entries: the host starts consent with its own challenge, after the login was accepted, and the checker gives each entry kind its own rules ([ADR 0024](0024-core-ir-as-a-narrow-waist.md), entries). The surface has no such reason.

How should a tenant write consent, and how does the editor show it, without changing what the IR means?

## Decision Drivers

* Charter principle 5: common flows are short to write and easy to read.
* Charter principle 1 and success criterion 3: a credential can never be verified on the consent challenge; the checker must keep proving that.
* ADR 0024: the IR is a narrow waist. A surface change must not change what the IR means, or it would need an IR format bump.
* Stable identity (dsl-and-ir.md principle 3): step ids come from YAML keys.
* Errors point at what the author wrote.

## Considered Options

1. **A. Consent steps at the end of `login.steps`; the compiler splits at the first step that calls `consent.record`.**
2. **B. A step level marker** (`on: consent`) on each step that runs on the consent challenge.
3. **C. Keep the `consent:` section in YAML; show it inline in the editor only.**
4. **D. One IR entry for both challenges**, with the engine suspending at the consent step until the host's consent challenge arrives.

## Decision Outcome

Chosen: **A.** A flow is one list of steps. The step that asks for consent, by showing the `consent` screen or calling `consent.record` in its body (directly, in an option of an `alternative`, or in nested `steps`), and every step after it lower to the `consent` entry; the steps before it lower to the `authenticate` entry. The split follows time: everything from that step on happens after the sign in was accepted, on the host's consent challenge. A flow with no such step has no consent entry, and the host's consent is granted without a screen, as before.

```yaml
login:
  steps:
    identify: { screen: identifier, use: identity.resolve }
    credential: { retry: 3, screen: password, use: password.verify }
    consent:
      if: { not: client.first_party }
      screen: consent
      use: consent.record
```

* **IR:** unchanged. The same two entries, `(entry login authenticate ...)` and `(entry consent consent ...)`, with the same ids, so existing published versions, stored flow states, conformance vectors and the IR format are untouched. The consent entry's source position is the step that starts it.
* **Checker:** a credential step placed after the consent step lands in the consent entry, which refuses it at the YAML that wrote it (`CheckerTest.aConsentEntryCannotVerifyACredential`). New: an `authenticate` entry may not show the consent screen or call `consent.record` (`CheckerTest.consentBelongsOnTheConsentChallenge`; since [ADR 0025](0025-host-neutral-boundaries-for-embedding.md) the catalogs say which screens and handlers belong in consent entries only, and the compiler receives them from the app through `FlowCompiler.Options`), so no IR, however produced, asks for consent before the sign in and then grants it without a screen.
* **Old YAML:** a top level `consent:` is refused with the fix in the message: "consent is a step of 'login' now: move these steps to the end of 'login.steps'" (`diagnostics/consent.yaml`). The Admin UI reads an old source (a stored version's YAML, a draft) by appending its consent steps to the steps, suffixing an id that would clash, so an old version opens migrated (`flow.test.ts`); if the old section does not start with a step that asks for consent, it refuses to open it rather than move the steps before that into the sign in. Faye has no released tenants yet, so refusing the old key costs one move per draft.
* **Admin UI:** one "Steps" list. A divider, "Consent: runs after sign in, on the consent challenge", sits above the first consent step; the graph ends the sign in with "signed in" there and the consent steps with "consented". The Consent block in the palette is an ordinary step. `consentStart` in `lib/flow.ts` mirrors the compiler's rule; the server stays the authority (AGENTS.md rule 5), since the checker's findings and paths come from the compiled IR.

### Consequences

* Good, because a flow reads as what happens, in order, and consent is one block among the others.
* Good, because nothing below the surface changes: no IR format bump, no migration, no new vector.
* Good, because a misplaced credential after consent is still refused by the checker at its YAML line.
* Bad, because the split is implicit: a step before consent that calls `consent.record` inside an option moves every later step to the consent challenge. The editor's divider makes the split visible, and the checker refuses credentials after it.
* Bad, because the editor and the compiler both encode the rule (`consentStart`, `FlowCompiler.recordsConsent`). Both are a few lines and tested against the shipped flows.
* Neutral: the top level key stays `login`, now meaning the whole way in rather than only the authenticate entry.

### Confirmation

1. `cd server && ./gradlew :faye-dsl:test :faye-check:test`: `FlowCompilerTest.stepsFromConsentOnLowerToTheConsentEntry`, `noConsentStepNoConsentEntry`, the golden files `acme.ir` and `partner-consent.ir` (unchanged: the same IR as before), the `consent.yaml` diagnostic, and `CheckerTest.aConsentEntryCannotVerifyACredential`.
2. `cd web && pnpm test`: the shipped flows round trip through the editor, `partner consent` is built from blocks with consent as the third step, and an old `consent:` section opens migrated.
3. `deploy/smoke.sh` signs in through a third party application of `acme` and sees the consent screen (unverified in this change: needs the compose stack).

Open gap: item 3 has not been run against this change.

## Pros and Cons of the Options

### A. Steps split at the first consent step

* Good, because it needs no new keyword and matches the order things happen.
* Good, because the IR, checker and engine are untouched.
* Bad, because the split rule is implicit (see Consequences).

### B. A step level marker

* Good, because explicit.
* Bad, because a new key every consent step must carry, and a marked step between unmarked ones has no meaning in time: the consent challenge comes after the whole sign in. It needs a rule that marked steps are contiguous and last, which is A plus a keyword.

### C. Inline in the editor only

* Good, because no compiler change.
* Bad, because the YAML, which is the source tenants read and review (charter, "What Faye is" 2), keeps the category the change removes, and the editor and YAML views would disagree on the shape.

### D. One IR entry for both challenges

* Good, because the IR would mirror the surface.
* Bad, because it changes what the IR means (an entry spanning two host challenges), so it needs an IR format bump, a new suspension kind in the engine, new checker rules and new vectors, all to change how a flow is written. ADR 0024 keeps the IR about authentication, not about the surface.
