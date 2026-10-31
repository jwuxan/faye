# Architecture decision records

Format: [MADR 4](https://adr.github.io/madr/). One file per decision, `NNNN-title.md`, numbered in order and never renumbered. Change a decision by adding a new record that supersedes the old one. The short decision log D1 to D13 is in [roadmap/decisions-and-risks.md](../roadmap/decisions-and-risks.md).

| # | Decision | Status |
|---|---|---|
| [0001](0001-validate-ir-with-json-schema.md) | Validate IR with JSON Schema instead of hand written checks | Proposed |
| [0002](0002-enforce-null-safety-with-jspecify-and-nullaway.md) | Enforce null safety with JSpecify and NullAway | Accepted |
| [0003](0003-enforce-java-format-and-lint-in-the-build.md) | Enforce Java format and lint in the build | Accepted |
| [0004](0004-identity-dependencies.md) | Dependencies for M2 credentials: webauthn4j, Spring Security Argon2, uqr | Accepted |
| [0005](0005-m2-flow-forms-and-screens.md) | M2 flow forms, predicates and screens | Accepted |
| [0006](0006-assurance-amr-acr.md) | Computing acr, enforcing acr_values, and reusing sessions | Accepted |
| [0007](0007-fuzz-and-adversarial-testing.md) | Fuzz and adversarial testing | Proposed |
| [0008](0008-m3-checks-versions-and-tenant-policy.md) | M3: static checks, durable versions and tenant policy | Accepted |
| [0009](0009-m4-javascript-hooks-on-quickjs-wasm.md) | M4: JavaScript hooks on QuickJS in WebAssembly | Accepted |
| [0010](0010-m5-admin-ui.md) | M5: Admin UI | Proposed |
| [0011](0011-m6-preview-loop.md) | M6: Preview loop | Proposed |
