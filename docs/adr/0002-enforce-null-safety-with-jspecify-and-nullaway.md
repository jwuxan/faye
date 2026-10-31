---
status: accepted
date: 2026-10-30
decision-makers: Josh Wu
---

# Enforce null safety with JSpecify and NullAway

## Context and Problem Statement

Plenty of server values can legitimately be null. `FlowState.awaiting` is null once a flow finishes. `Env.Auth.subject` is null until a credential binds one. `Outcome.Ok.amr` is null when a handler proved everything it declares. `Acr.of` returns null when nothing verified. Today the only place this is recorded is Javadoc ("or null"), and the compiler has no way to check it. A missed null check in the engine shows up as a `NullPointerException` in the middle of a login, or worse, as a branch that quietly takes the wrong path on a value that decides authentication.

We want every type non-null by default, nullable only where it is explicitly annotated, and the compiler to reject code that breaks the rule.

## Decision Drivers

* The rule should be written in the code itself and checked on every `./gradlew build`, so it can't go stale the way Javadoc does.
* Non-null is the default, so the annotations mark only the exceptions.
* It has to coexist with `-Xlint:all -Werror` (AGENTS.md rule 8) and add no runtime behaviour.
* The new dependencies should be few, small, and pinned.

## Considered Options

* JSpecify annotations, checked by NullAway running in Error Prone
* JSpecify annotations, checked by the Checker Framework Nullness Checker
* Annotations only (JSpecify or JetBrains), with checking left to the IDE
* No annotations: `Objects.requireNonNull` at boundaries, `Optional` for absent values

## Decision Outcome

Chosen option: **JSpecify annotations, checked by NullAway in Error Prone**. Every package gets a `package-info.java` annotated `@NullMarked`, which makes non-null the default. Values that can be null carry `@Nullable` (from `org.jspecify.annotations`) on the parameter, return type, field or record component. NullAway reports violations as compile errors.

### Plan

1. Pin `org.jspecify:jspecify`, `com.google.errorprone:error_prone_core`, `com.uber.nullaway:nullaway` and the `net.ltgt.errorprone` Gradle plugin in `gradle/libs.versions.toml`.
2. In `server/build.gradle.kts`, apply the plugin to every subproject. Error Prone runs **only** NullAway (`disableAllChecks`, then `NullAway:ERROR`), so its other checks can't start failing `-Werror` builds. The options are `NullAway:AnnotatedPackages=faye` and `NullAway:JSpecifyMode=true`.
3. Add `-XDaddTypeAnnotationsToSymbol=true`. JSpecify mode on a JDK 21 javac needs it; later javac versions ignore it.
4. Add a `package-info.java` with `@NullMarked` to every package under `src/main/java`.
5. Annotate the values that really can be null, and fix the call sites NullAway reports. Where something is non-null only because of an invariant NullAway can't see, use `Objects.requireNonNull` with a message; don't annotate it nullable.
6. Add a rule to AGENTS.md that new packages need a `@NullMarked` `package-info.java`.

### Consequences

* Good, because nullability is part of each signature and the build enforces it.
* Good, because JSpecify is the shared standard: Spring Framework 7, Guava and the IDEs read the same annotations.
* Good, because `AnnotatedPackages=faye` checks every `faye` package even when its `package-info.java` is missing. The `@NullMarked` file is still what tells IDEs and other tools.
* Bad, because it adds three build dependencies and a Gradle plugin. Only `jspecify` (annotations only, no transitive dependencies) is on the runtime classpath.
* Bad, because NullAway doesn't track invariants such as "subject is set once a login section succeeds", so those sites need an explicit `requireNonNull`.
* Neutral, because test sources share package names with main sources, so `AnnotatedPackages=faye` checks them too.

### Confirmation

`cd server && ./gradlew build` passes with `-Werror`. To check that enforcement is live, add `String s = null;` to any `faye` class. NullAway must fail the compile with `[NullAway] assigning @Nullable expression to @NonNull field` or a similar error.

## Pros and Cons of the Options

### JSpecify + NullAway

* Good, because it is a single Error Prone check that runs inside javac. NullAway's authors report build overhead under about 10% (**unverified for this build**; to verify, time `./gradlew clean compileJava` with and without the plugin).
* Good, because JSpecify mode checks generics and arrays as well.
* Bad, because JSpecify mode is still being completed upstream, and some generic cases may need suppressions or workarounds.
* Bad, because Error Prone has to open javac internals (`--add-exports`), which the Gradle plugin configures.

### JSpecify + Checker Framework Nullness Checker

* Good, because it is sound, and it handles initialization and map keys more precisely.
* Bad, because it is noticeably slower and needs many more annotations, including initialization annotations, `@KeyFor`, and stub files for third party libraries.

### Annotations only, IDE checked

* Good, because it adds no build tooling.
* Bad, because nothing enforces it: CI passes no matter what the annotations say.

### No annotations, runtime checks

* Good, because it adds no dependencies.
* Bad, because failures only appear at runtime, and `Optional` doesn't fit record components or parameters.

## More Information

**Risk (verified for Docker, not yet for CI).** NullAway's documentation says JSpecify mode on JDK 21 needs javac 21.0.8 or later. On 2026-10-31, `eclipse-temurin:21-jdk` resolved to 21.0.13, and `docker build --target build server/` compiled every main source with NullAway enabled. CI (`actions/setup-java` with `"21"`) also resolves to the latest 21.x. To confirm it, check that `./gradlew build` passes on the first CI run.

Versions as of this record: jspecify 1.0.1, error_prone_core 2.51.0, nullaway 0.14.3, `net.ltgt.errorprone` 5.1.1.
