---
status: proposed
date: 2026-10-31
---

# Bootstrap Gradle without a wrapper jar

## Context and Problem Statement

The wrapper jar is deliberately not committed under AGENTS.md rule 7. Consequently a fresh checkout cannot run `./gradlew`, including CI and the server Docker build. Gradle publishes the distribution and its checksum, not a standalone wrapper jar download.

## Decision Drivers

* No committed binaries; downloaded tools must be checksum pinned.
* One distribution version for local builds, CI and Docker.
* Keep `./gradlew` usable from a fresh checkout.

## Considered Options

* Commit the wrapper jar: standard wrapper behavior, but violates rule 7.
* CI-only setup-gradle `gradle-version` plus a Gradle Docker image: avoids custom bootstrap code, but leaves local `./gradlew` broken and introduces two version pins.
* Bootstrap the distribution in `gradlew`: one version and checksum in wrapper properties, but adds maintained shell code.

## Decision Outcome

Chosen option: **bootstrap in `gradlew`** when the jar is absent. Download the distribution specified in wrapper properties, verify `distributionSha256Sum`, extract with the JDK's `jar` tool, and exec Gradle with the original arguments. Cache under ignored `server/.gradle/`; exclude it from the Docker context. If the jar exists, preserve the standard wrapper path. Windows `gradlew.bat` is unchanged and still requires a locally supplied wrapper jar.

### Consequences

* Good, because local, CI and Docker builds use the same checksum-pinned distribution without committed binaries.
* Bad, because `gradle wrapper` regeneration overwrites the custom bootstrap; preserve it when upgrading.
* Bad, because first use requires curl or wget, sha256sum or shasum, and a JDK containing jar.

### Confirmation

`https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256` publishes `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a` (checked on 2026-10-31).

From a fresh checkout, `cd server && ./gradlew build` must work without the wrapper jar. CI server and e2e jobs must pass; locally exercise `FAYE_REQUIRE_REDIS=1 ./gradlew build` with Redis and `docker compose -f deploy/compose.yaml up -d --build` followed by `deploy/smoke.sh`. CI confirmation remains unverified until those jobs run on the change.
