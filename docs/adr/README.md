# Architecture decision records

Format: [MADR 4](https://adr.github.io/madr/). One file per decision, `NNNN-title.md`, numbered in order and never renumbered. Change a decision by adding a new record that supersedes the old one. Status is `Proposed` (planned, not implemented), `In progress` (started, or gaps remain), `Accepted` (implemented, every gap closed, confirmation passes; set by a human), or `Superseded by NNNN`; see [AGENTS.md](../../AGENTS.md#decisions). The short decision log D1 to D13 is in [roadmap/decisions-and-risks.md](../roadmap/decisions-and-risks.md).

| # | Decision | Status |
|---|---|---|
| [0001](0001-validate-ir-with-json-schema.md) | Validate IR with JSON Schema instead of hand written checks | Accepted |
| [0002](0002-enforce-null-safety-with-jspecify-and-nullaway.md) | Enforce null safety with JSpecify and NullAway | Accepted |
| [0003](0003-enforce-java-format-and-lint-in-the-build.md) | Enforce Java format and lint in the build | Accepted |
| [0004](0004-identity-dependencies.md) | Dependencies for M2 credentials: webauthn4j, Spring Security Argon2, uqr | Accepted |
| [0005](0005-m2-flow-forms-and-screens.md) | M2 flow forms, predicates and screens | Accepted |
| [0006](0006-assurance-amr-acr.md) | Computing acr, enforcing acr_values, and reusing sessions | Accepted |
| [0007](0007-fuzz-and-adversarial-testing.md) | Fuzz and adversarial testing | In progress |
| [0008](0008-m3-checks-versions-and-tenant-policy.md) | M3: static checks, durable versions and tenant policy | Accepted |
| [0009](0009-m4-javascript-hooks-on-quickjs-wasm.md) | M4: JavaScript hooks on QuickJS in WebAssembly | Accepted |
| [0010](0010-m5-admin-ui.md) | M5: Admin UI | Accepted |
| [0011](0011-m6-preview-loop.md) | M6: Preview loop | Accepted |
| [0012](0012-bootstrap-gradle-without-a-wrapper-jar.md) | Bootstrap Gradle without a wrapper jar | Proposed |
| [0013](0013-admin-console-shell-and-flow-workspace.md) | Admin console: sidebar shell, flow workspace and hooks section | Accepted |
| [0014](0014-tenant-user-directory.md) | An external user directory per tenant, read only in the admin console | Superseded by 0018 |
| [0015](0015-oauth2-client-management.md) | Applications in the admin console: read only from the authorization server | Accepted |
| [0016](0016-operator-account-and-console-preferences.md) | Operator account and console preferences | Proposed |
| [0017](0017-one-sqlite-database.md) | One SQLite database for Faye's stores and Hydra | Accepted |
| [0018](0018-two-tenants-and-an-open-local-console.md) | Two tenants, each with its applications and user pool, and a console without sign in | Accepted |
| [0019](0019-a-five-service-compose-stack.md) | A five service compose stack | Superseded by 0023 |
| [0020](0020-versioned-schema-migrations-with-flyway.md) | Versioned schema migrations with Flyway | Accepted |
| [0021](0021-official-hydra-admin-client.md) | Ory's official Java client for Hydra's admin API | Accepted |
| [0022](0022-passkey-ceremonies-with-simplewebauthn.md) | Passkey ceremonies in the Login UI with SimpleWebAuthn, algorithms from the server | Accepted |
| [0023](0023-one-container-many-processes.md) | One container, many processes | In progress |
| [0024](0024-core-ir-as-a-narrow-waist.md) | The Core IR as a narrow waist: entries, format versions, handler and screen catalogs, effects after the compare and set, browser bound flows | In progress |
| [0025](0025-host-neutral-boundaries-for-embedding.md) | Host neutral boundaries for embedding: host parameters, credential issuers and flow outcomes | In progress |
| [0026](0026-consent-as-a-step.md) | Consent as a step of the sign in, not a section of its own | In progress |
