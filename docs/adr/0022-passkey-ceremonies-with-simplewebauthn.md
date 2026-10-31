---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# Passkey ceremonies in the Login UI with SimpleWebAuthn, algorithms from the server

## Context and Problem Statement

`web/apps/login/lib/webauthn.ts` drives the WebAuthn ceremonies by hand: it converts base64url to and from `ArrayBuffer`, serializes `PublicKeyCredential` into JSON field by field, and hard codes the algorithms it offers at registration (ES256 `-7`, RS256 `-257`). The server keeps its own copy of that list in `IdentityHandlers.ALGORITHMS`, which verifies them. The user decided (2026-10-31) to delegate the browser side to a maintained library and keep the list in one place.

## Decision Drivers

* The browser side of WebAuthn (encoding, `toJSON`, conditional mediation, aborting a pending autofill request) is fiddly and browser dependent; a widely used library tracks it.
* The algorithms the browser offers must be the ones the server verifies, from one source.
* The Login UI makes no external requests ([ADR 0004](0004-identity-dependencies.md)).
* Screens are typed (AGENTS.md rule 6); a protocol change goes through `schema/` first (rule 1).

## Considered Options

1. Browser side
   * A. `@simplewebauthn/browser` 13.3.0
   * B. The native `PublicKeyCredential.toJSON()` and `parseCreationOptionsFromJSON()` / `parseRequestOptionsFromJSON()`
   * C. Keep the hand written code
2. Algorithm list
   * A. The passkey screen's `register` carries `algorithms`, filled by the engine from one constant that `IdentityHandlers` also uses
   * B. Keep two copies

## Decision Outcome

1A and 2A.

* `@simplewebauthn/browser` 13.3.0 (MIT, published 2026-03-10). 14.0.0 (2026-09-02) is a new major that has not been reviewed here; staying on 13.x keeps the API this record was written against. `webauthn.ts` keeps its three functions and their signatures except where noted, built on `startAuthentication({ optionsJSON, useBrowserAutofill })`, `startRegistration({ optionsJSON })`, `browserSupportsWebAuthnAutofill()` and `WebAuthnAbortService`. A dismissed or failed ceremony still resolves `null`.
* Protocol: `PasskeyScreen.register` gains a required `algorithms`, an array of COSE algorithm ids (integers), in order of preference. The engine fills it from `ScreenType.PASSKEY_ALGORITHMS` (`-7`, `-257`), and `IdentityHandlers` builds the parameters webauthn4j verifies against from the same constant. The Login UI offers exactly that list.
* The browser now posts what SimpleWebAuthn produces, which has more fields than before (`authenticatorAttachment`; on registration `response.publicKey`, `response.publicKeyAlgorithm`, `response.authenticatorData`). `IdentityFlowTest.theJsonSimpleWebAuthnPostsVerifies` checks that webauthn4j accepts that shape for registration and assertion.
* Saving the enrolled passkey is the identity module's job (`webauthn.register` through `UserStore`, [ADR 0017](0017-one-sqlite-database.md)), not the engine's; this record changes only the browser side and the algorithm list.

### Consequences

* Good, because no WebAuthn encoding code is ours in the browser, and the library cancels a pending autofill request when another ceremony starts.
* Good, because adding an algorithm is one change, on the server.
* Bad, because it is a new dependency of the Login UI. It has no runtime dependencies of its own (its `package.json` lists none; checked 2026-10-31).
* Bad, because it is a screen protocol change: schema, Java and the generated types change together.

### Confirmation

* `cd server && ./gradlew build` passes, including the screen schema checks, `IdentityFlowTest`, and the test that sends SimpleWebAuthn shaped registration and assertion responses through `webauthn.register` and `webauthn.assert`.
* `cd web && pnpm generate && pnpm typecheck && pnpm lint && pnpm test && pnpm build` passes, and CI's diff check finds the generated types match the schema.

Run on 2026-10-31: `cd server && ./gradlew build` passes (`IdentityFlowTest` 8, `theJsonSimpleWebAuthnPostsVerifies` included); `cd web && pnpm generate && git diff --exit-code -- packages/contract/src && pnpm typecheck && pnpm lint && pnpm test && pnpm build` passes.

## Pros and Cons of the Options

### `@simplewebauthn/browser`

* Good, because it is widely used and handles conditional mediation, aborts and encoding.
* Bad, because it is one more dependency.

### Native JSON helpers

* Good, because they need no dependency.
* Bad, because `parseCreationOptionsFromJSON` and `parseRequestOptionsFromJSON` are not in every browser the Login UI supports, so a fallback would keep the hand written code anyway.

### Keep the hand written code

* Good, because it works today.
* Bad, because the encoding and serialization stay ours to maintain, and the algorithm list stays duplicated.

### Two copies of the algorithm list

* Good, because there is no protocol change.
* Bad, because they can drift: the browser could offer an algorithm the server rejects.
