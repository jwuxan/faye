---
status: accepted
date: 2026-10-30
decision-makers: Josh Wu
---

# Dependencies for M2 credentials: webauthn4j, Spring Security Argon2, uqr

## Context and Problem Statement

M2 brings real credentials: Argon2 passwords, TOTP, and passkeys (WebAuthn registration and assertion), plus a QR code on the TOTP enrollment screen. The plan named webauthn4j, Spring Security's Argon2 and "a TOTP library". Each one is a new dependency, and AGENTS.md asks for a record.

## Decision Drivers

* Verifying a credential is security critical code that we should not write ourselves where a mature library exists.
* Prefer the standard library and existing dependencies.
* Stay compatible with Spring Boot 3.5, which manages Jackson 2.
* The Login UI must keep working without external requests.

## Considered Options

* WebAuthn: webauthn4j 0.31 (current), webauthn4j 0.30, Yubico java-webauthn-server, hand written verification.
* Argon2: Spring Security `Argon2PasswordEncoder` (needs Bouncy Castle), a JNI binding (argon2-jvm).
* TOTP: a library (e.g. java-otp), about 30 lines on `javax.crypto.Mac`.
* QR code: `qrcode` (npm), `uqr`, rendering on the server.

## Decision Outcome

1. **webauthn4j-core 0.30.3.RELEASE** (test: webauthn4j-test, its authenticator emulator). 0.31 moved to Jackson 3 (`tools.jackson`), while Spring Boot 3.5 manages Jackson 2. 0.30.3 is the newest Jackson 2 release. Its signatures carry `org.jetbrains:annotations`, which javac needs to read the classes without warnings under `-Werror`, so that is a `compileOnly` dependency.
2. **spring-security-crypto** (version from the Boot BOM) with **bcprov-jdk18on 1.82** for Argon2id (`defaultsForSpringSecurity_v5_8`), and **spring-jcl** at runtime for the logging API it uses.
3. **TOTP on the JDK**: RFC 6238 with HMAC-SHA1 in `faye.identity.Totp`, checked against the RFC's appendix B vectors. A library would cost more than the code it replaces.
4. **uqr 0.1.3** in the Login UI. It has zero dependencies, is MIT licensed and ships its own types. The page draws its module matrix as SVG, so nothing is injected as HTML. `qrcode` pulls in a CLI dependency tree (yargs, pngjs).

### Consequences

* Good, because credential verification (attestation, signature, counter, origin, RP id, challenge) is done by a widely used library and tested end to end with its emulator (`IdentityFlowTest`).
* Bad, because we are one major webauthn4j line behind. Moving to 0.31 waits on Spring Boot 4 / Jackson 3, or on isolating webauthn4j's Jackson.
* Bad, because Bouncy Castle is a large jar, needed only for Argon2.

### Confirmation

`cd server && ./gradlew build`: `TotpTest` (RFC 6238 vectors) and `IdentityFlowTest` (Argon2 sign in, passkey registration and assertion through the engine) pass. `cd web && pnpm build` passes.

## More Information

Unverified: that webauthn4j 0.30.x keeps getting security fixes now that 0.31 exists. Check its release notes before production hardening.
