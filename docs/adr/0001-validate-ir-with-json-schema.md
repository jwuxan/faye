---
status: accepted
date: 2026-10-31
decision-makers: Josh Wu
---

# Validate IR with JSON Schema instead of hand written checks

## Context and Problem Statement

The IR is an S expression tree stored as JSON arrays ([D2](0036-retire-the-roadmap.md)). No S expression text is parsed anywhere: Jackson (`ObjectMapper.readTree`) parses the JSON, and the `(seq …)` notation is for display only.

What is hand written is the shape check. `IrCodec.readNode` (about 100 lines) checks form names, arity, id and name patterns and literal types, which restates `schema/ir.schema.json`. Each form still to come (`choose`, `when`, `retry`, `hook`, `set`, predicates) would be written twice, and the two copies can drift.

Should we keep validating the IR by hand, or let a third party library enforce the schema we already maintain?

## Decision Drivers

* One grammar: `schema/` is the source of truth (AGENTS.md rule 1).
* Errors must keep their JSON pointer (`IrException.at()`), which source maps and the Admin UI rely on.
* Prefer existing dependencies; add none without a reason.
* Keep D2: no Lisp runtime and no second syntax.

## Considered Options

* Text S expression reader (edn-java, the Clojure reader)
* Jackson polymorphic types (`@JsonTypeInfo`, `As.WRAPPER_ARRAY`)
* networknt `json-schema-validator` at runtime, against `schema/ir.schema.json`
* Keep hand written checks in `IrCodec`

## Decision Outcome

Chosen option: **networknt `json-schema-validator` at runtime**. It enforces the schema we already maintain, is already in the build (`IrCodecTest` uses it, version 1.5.9), and reduces `readNode` to a plain mapper from JSON to records.

### Plan

1. Put `schema/ir.schema.json` on `faye-ir`'s classpath through Gradle `processResources` from the repo root. Commit no copy.
2. Move `json-schema-validator` from `testImplementation` to `implementation` in `faye-ir`.
3. `IrCodec.readFlow(JsonNode)` validates first. On failure it throws `IrException` carrying the error's instance location as the pointer.
4. Reduce `readNode` to a switch on the form name that builds the records. Delete `arity`, the `ID` and `NAME` patterns, and the literal type checks.
5. Keep in Java only what the schema cannot express: duplicate section names, duplicate option ids, and choosing `Integer` or `Long` for literals. `IrCodec.readPredicate` is also called directly by the DSL compiler for `if:` predicates, outside any flow, so it validates against `#/$defs/predicate` of the same schema, then maps; `Predicate.NAMESPACES` and the path pattern are deleted.
6. Leave `IrCodecTest` unchanged, including the `/3/1/2/0` pointer assertion.
7. Later, when the Admin UI edits the IR, validate it in TypeScript with ajv against the same schema.
8. The server image is built with `server/` as its Docker context, which does not contain `schema/`. Compose builds `faye` from the repo root instead (`context: ..`, `dockerfile: server/Dockerfile`), the Dockerfile copies `server/` and `schema/`, and `server/Dockerfile.dockerignore` sends nothing else.

### Consequences

* Good, because each new form is added once, in `schema/`, and the Java side only maps it.
* Good, because server and Admin UI validate against the same file.
* Bad, because the validator and its transitive dependencies join the runtime classpath of `faye-ir` and everything that depends on it.
* Bad, because schema error messages are more generic than the current hand written ones (for example "seq takes …").
* Neutral, because validation runs on IR reads, which happen at publish and load time, never per login request. Today `readFlow` is only called from tests.

### Confirmation

`cd server && ./gradlew build` passes (`-Xlint:all -Werror`, unit and conformance tests), with `IrCodecTest` unchanged.

## Pros and Cons of the Options

### Text S expression reader

* Bad, because there is no S expression text to read. It would add a second syntax and parser surface, against D2.

### Jackson polymorphic types

* Good, because it needs no new dependency.
* Bad, because `WRAPPER_ARRAY` handles only `[type, value]`, not variadic positional forms like `["seq", a, b, c]`. A custom `JsonDeserializer` would be the same hand written code in another place.

### networknt `json-schema-validator`

* Good, because it is already in the build and supports draft 2020-12, the schema's dialect.
* Bad, because of the risk below.

### Keep hand written checks

* Good, because there is no change and no new runtime dependency, and nothing is broken today.
* Bad, because the cost grows with every new form, and the copies can drift without notice until a test catches it.

## More Information

**Outcome of the risk.** It held, and worse: with `oneOf`, validation grew exponentially with nesting and ran out of memory on `passkey-totp.json`. [ADR 0005](0005-m2-flow-forms-and-screens.md) changed `$defs/node` to dispatch on the form name with `if`/`then`, and `IrCodec` reports the error with the deepest instance location, so the `/3/1/2/0` assertion holds. Predicates still use a small `oneOf`; an unknown operator there reports only the top level error (`must be valid to one and only one schema`), which `FlowCompilerTest` pins. The fallback was not needed.

**Risk (as recorded before implementation).** `$defs/node` is a `oneOf` over every form, so for an unknown form the validator reports errors from every branch. The reported pointer may be `/3/1/2` rather than `/3/1/2/0`. If so, either choose the most specific error, or change the schema to dispatch on the first element with an `if`/`then` chain. Changing the schema comes first, per AGENTS.md rule 1. Confirm this with a test before plan step 3.

**Fallback.** If the risk holds and neither fix is clean, keep the hand written checks until `choose`, `when` and `retry` land, then revisit.
