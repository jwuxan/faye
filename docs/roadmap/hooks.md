# JavaScript hooks

The DSL fixes the skeleton. Hooks provide fine grained decisions inside it: risk scoring, tenant specific routing, enrichment from an internal service.

## Contract

```js
// risk.js
export default async function (ctx, cap) {
  const score = await cap.http.getJson('ip-reputation', { ip: ctx.request.ip })
  return { set: { risk: score.value > 50 ? 'high' : 'low' } }
}
```

`ctx` is a read only, deep frozen snapshot of `request`, `client`, `hydra`, `tenant`, `user`, `vars` (and `options`, the enabled option ids, for the hook of a `choose`). It never contains credentials, secrets or `auth.*`. The source is a script with one `export default` at the start of a line; it has no imports.

Allowed return values:

| Return | Effect |
|---|---|
| `{ set: { … } }` | Merge into `vars.*` only: 1 to 16 names, each a string (up to 1024 characters), integer or boolean. Not allowed for the hook of a `choose` |
| `{ fail: "reason" }` | Fail the current step (subject to enclosing `retry`); the reason is an id such as `too_risky` |
| `{ pick: "optionId" }` | Only valid when the hook is the first child of a `choose`; selects one of the declared, enabled options |
| `{}` | No op |

Anything else, and any throw, timeout or memory exhaustion, is a hook error, which fails the step with `hook_error`. The engine checks the result, not the sandbox, so even a sandbox bug cannot widen this table.

**Hooks can never** succeed a flow, skip a step, bind a subject, write `amr` / `acr`, or emit a screen. This closed output space is what keeps the static checks in [dsl-and-ir.md](dsl-and-ir.md) valid even though the checker cannot see inside JavaScript.

## Capabilities

Hooks have no ambient I/O. Each hook declares the capabilities it needs; the tenant grants them in config.

```yaml
# in the flow
hooks:
  risk:
    file: risk.js               # next to the flow; or `source: |` with the JavaScript inline
    capabilities:
      http: [ip-reputation]     # named, preconfigured egress targets only
    timeout_ms: 200             # default 200, at most 2000
login:
  steps:
    risk: { hook: risk }                    # a hook in a step: set, fail or {}
    credential:
      pick: route                           # the hook of a choose: pick, fail or {}
      alternative: { … }

# in the server config
faye:
  tenants:
    acme:
      egress: [ip-reputation]   # targets this tenant's hooks may call; none when unset
  egress:
    ip-reputation: https://reputation.internal/score
```

Initial capability set: `http.getJson(target, query)` (a `GET` with the query as URL parameters, JSON back, no redirects, at most 64 KiB) against targets the hook declared and the tenant granted, and `log.info`. No filesystem, no timers beyond the deadline, no raw network. Publishing fails if a hook declares a target its tenant does not grant; each call is checked again at run time.

## Sandbox

`HookRuntime` interface in `faye-hooks`, implemented on QuickJS compiled to WebAssembly and run by a pure Java Wasm runtime (quickjs4j); see [ADR 0009](../adr/0009-m4-javascript-hooks-on-quickjs-wasm.md) for why not GraalJS:

1. One fresh QuickJS instance per invocation, from bytecode cached by hook hash. Its only imports are the host functions behind `cap`; no host classes, no IO.
2. Wall clock deadline (the hook's `timeout_ms`, egress included) and a 16 MiB linear memory cap; exceeding either fails the step. At most 8 hooks run at once, 8 more may wait, and the rest are refused.
3. The hook definition (source, declared capabilities, timeout) is stored by the hash of its canonical JSON; a flow version pins hook hashes, so editing a hook requires publishing a new flow version.

## Authoring experience

1. Admin UI provides a Monaco editor with TypeScript definitions for `ctx` and `cap` generated from the schema.
2. "Test hook" runs the hook server side against a sample `ctx` and shows the return value and logs.
3. Hook source is versioned alongside flows; diff view on publish.
