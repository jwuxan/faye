---
status: accepted
date: 2026-10-30
decision-makers: Josh Wu
---

# Enforce Java format and lint in the build

## Context and Problem Statement

Java style is held only by convention. Nothing fails when a file drifts from the 4 space, wide line layout the code uses, so every diff can carry layout noise and review time goes to whitespace. Rust has `cargo fmt --check` and `cargo clippy -D warnings`: one canonical layout, checked in CI, and a linter whose warnings are errors. We want the same for the server: `./gradlew build` fails on unformatted code or on a lint violation, and one command fixes the layout.

## Decision Drivers

* One canonical layout produced by a tool, not argued in review (the `cargo fmt` model).
* Strict: format and lint failures fail `./gradlew build`, which CI already runs. That matches `-Werror` and `--max-warnings 0` (AGENTS.md rule 8).
* The formatter and the linter must never disagree, so a formatted file always passes lint.
* Low churn on the existing code: 4 space indent, lines up to about 120 columns.
* Tools are resolved by Gradle from Maven Central and pinned in `libs.versions.toml`. No binaries are committed (AGENTS.md rule 7).

## Considered Options

* Spotless with palantir-java-format, plus Checkstyle for lint only
* Spotless with google-java-format (Google or AOSP style), plus Checkstyle
* Checkstyle alone, with layout rules (`Indentation`, `WhitespaceAround`, and so on)
* No enforcement, `.editorconfig` only

## Decision Outcome

Chosen option: **Spotless with palantir-java-format, plus Checkstyle for lint only**, with a root `.editorconfig` so editors produce close to formatted output before Spotless runs.

* Spotless 8.10.4 applies palantir-java-format 2.103.0 to every subproject's Java sources. It also removes unused imports, forbids wildcard imports, trims trailing whitespace, and ends each file with a newline. It applies ktlint to `*.gradle.kts`, with `chain-method-continuation` disabled so version catalog accessors stay on one line. `spotlessCheck` is part of `check`, so `./gradlew build` fails on unformatted code. `./gradlew spotlessApply` fixes it, the way `cargo fmt` does.
* Checkstyle 14.3.0 runs `server/config/checkstyle/checkstyle.xml` at severity `error` with `maxWarnings = 0`. The config holds only checks that a formatter cannot fix: imports, naming, file structure, `NeedBraces` (one line guards like `if (x) return;` are allowed, as the code already uses them), and likely bugs (`EqualsHashCode`, `StringLiteralEquality`, `EqualsAvoidNull`, `FallThrough`, and so on). It has no layout checks, so it cannot fight the formatter. `LineLength` 120 matches the formatter's limit and only catches what the formatter cannot wrap, such as long string literals.
* Error Prone keeps running NullAway alone ([ADR 0002](0002-enforce-null-safety-with-jspecify-and-nullaway.md)). Turning on more Error Prone bug patterns is a separate decision.

### Consequences

* Good, because layout is no longer something anyone decides or reviews, and CI rejects drift without a new CI step.
* Good, because a formatted file passes lint by construction. Checkstyle only reports things a person has to fix.
* Bad, because the first `spotlessApply` rewrites most Java files once, so concurrent branches conflict. Run it when no branch is open on `server/`.
* Bad, because the Gradle build gains three tools (Spotless plugin, palantir-java-format, Checkstyle, plus ktlint fetched by Spotless). They affect build time only, never the runtime classpath.
* Neutral, because palantir-java-format ties itself to javac internals. A new JDK can break it until it is upgraded. Mitigation: bump it with the JDK, pinned in the catalog.

### Confirmation

`cd server && ./gradlew build` runs `spotlessCheck`, `checkstyleMain` and `checkstyleTest`, and passes. Adding a tab, a wildcard import, or a string literal compared with `==` to any Java file makes it fail. `./gradlew spotlessApply` followed by `./gradlew spotlessCheck` passes.

## Pros and Cons of the Options

### Spotless with palantir-java-format, plus Checkstyle

* Good, because palantir's 4 space indent and 120 column limit match the existing code. On the first run most short files did not change (for example `HostRequest.java`, whose record header is 115 columns).
* Good, because Spotless is the standard Gradle formatting plugin and also covers the Kotlin build scripts.
* Bad, because palantir is a fork of google-java-format maintained by one company, and it lags new JDKs.

### Spotless with google-java-format

* Good, because it is the most widely used Java formatter, and its AOSP variant uses 4 space indent.
* Bad, because its 100 column limit rewraps many existing lines, which means more churn and more vertical code than the codebase uses today.

### Checkstyle alone, with layout rules

* Good, because it adds a single tool.
* Bad, because it only reports problems and cannot fix them, so there is no `cargo fmt`. Layout rules also tend to conflict with IDE formatters.

### No enforcement, `.editorconfig` only

* Good, because it adds nothing to the build.
* Bad, because editors apply `.editorconfig` only partially and nothing checks it in CI, so it fails the "strict" driver.

## More Information

Versions were checked against Maven Central `maven-metadata.xml` on 2026-10-30. **Unverified:** that every editor the team uses honours `max_line_length` from `.editorconfig`. Spotless remains the source of truth either way.
