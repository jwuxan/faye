/**
 * M5 exit, the part a machine can check: the three M2 flows and the M4 risk flow can be built from
 * palette blocks and inspector edits alone (the same functions the UI calls), and each comes out
 * as YAML that compiles to the shipped flow.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { parse, stringify } from "yaml";
import {
  type Address,
  block,
  canonical,
  emptyFlow,
  entryAt,
  type FlowDoc,
  consentStart,
  fromBlock,
  fromYaml,
  FlowShapeError,
  insert,
  type ListRef,
  misfit,
  misplaced,
  move,
  isLegacy,
  type Literal,
  PALETTE,
  setting,
  withSetting,
  remove,
  removeEntry,
  rename,
  setHook,
  toYaml,
  updateBody,
  visitKeys,
} from "./flow.ts";
import { diffLines } from "./diff.ts";
import { locate } from "./locate.ts";
import { all, any, compare, describe, not } from "./predicate.ts";

const FLOWS = new URL("../../../../server/faye-app/src/main/resources/flows/", import.meta.url);
const shipped = (name: string) => readFileSync(new URL(name, FLOWS), "utf8");

const LOGIN: ListRef = { section: "login", owner: [], kind: "steps" };
const step = (i: number): Address => ({ section: "login", path: [["steps", i]] });
const option = (s: number, o: number): Address => ({ section: "login", path: [["steps", s], ["alternative", o]] });
const options = (s: number): ListRef => ({ section: "login", owner: [["steps", s]], kind: "alternative" });

/** Drops a palette block at the end of a list, as a drag from the palette does. */
function add(doc: FlowDoc, list: ListRef, name: string): FlowDoc {
  const at = list.owner.length === 0 ? (doc[list.section]?.length ?? 0) : Number.MAX_SAFE_INTEGER;
  return insert(doc, list, at, fromBlock(block(name), list.kind));
}

function sameAsShipped(doc: FlowDoc, file: string, inlineHooks: Record<string, string> = {}) {
  const expected = parse(shipped(file));
  // The editor writes hooks inline; a hook given as a file compiles to the same definition
  for (const [id, source] of Object.entries(inlineHooks)) {
    const { file: _file, ...rest } = expected.hooks[id];
    void _file;
    expected.hooks[id] = { source, ...rest };
  }
  const yaml = toYaml(doc);
  assert.equal(canonical(yaml), canonical(stringify(expected)), yaml);
  // And the YAML reads back into the same document
  assert.equal(canonical(toYaml(fromYaml(yaml))), canonical(yaml));
}

test("password only", () => {
  let doc = emptyFlow("password-only");
  doc = add(doc, LOGIN, "Identifier");
  doc = add(doc, LOGIN, "Password");
  sameAsShipped(doc, "password-only.yaml");
});

test("partner consent", () => {
  let doc = emptyFlow("partner-consent");
  doc = add(doc, LOGIN, "Identifier");
  doc = add(doc, LOGIN, "Password");
  doc = add(doc, LOGIN, "Consent");
  doc = rename(doc, step(2), "grant");
  doc = updateBody(doc, step(2), { if: not("client.first_party") });
  assert.equal(consentStart(doc.login), 2);
  sameAsShipped(doc, "partner-consent.yaml");
});

test("a step's path is kept next to its block (ADR 0029)", () => {
  const yaml = "flow: f\nversion: 1\nlogin:\n  steps:\n    credential:\n      block: password\n      path: /challenge/password\n";
  const doc = fromYaml(yaml);
  assert.equal(doc.login[0].body.path, "/challenge/password");
  assert.equal(canonical(toYaml(doc)), canonical(yaml));
});

test("consent starts at the first step that records it, nested or not", () => {
  const steps = fromYaml(`flow: f
version: 1
login:
  steps:
    identify: { screen: identifier, use: identity.resolve }
    ask:
      alternative:
        grant: { screen: consent, use: consent.record }
    after: { set: { done: true } }
`).login;
  assert.equal(consentStart(steps), 1);
  assert.equal(consentStart(steps.slice(0, 1)), 1);
});

test("the Consent block starts consent, and a literal that says consent.record does not, as in the compiler", () => {
  const steps = fromYaml(`flow: f
version: 1
login:
  steps:
    identify: { block: identifier }
    note: { set: { use: consent.record } }
    ask: { block: consent }
`).login;
  assert.equal(consentStart(steps), 2);
});

test("an old consent section that does not start by asking for consent is refused, not moved into the sign in", () => {
  assert.throws(
    () =>
      fromYaml(`flow: f
version: 1
login:
  steps:
    identify: { screen: identifier, use: identity.resolve }
consent:
  steps:
    audit: { set: { audited: true } }
    grant: { screen: consent, use: consent.record }
`),
    FlowShapeError,
  );
});

test("a consent section from before ADR 0026 opens as steps at the end", () => {
  const doc = fromYaml(`flow: f
version: 1
login:
  steps:
    identify: { screen: identifier, use: identity.resolve }
    grant: { set: { x: 1 } }
consent:
  steps:
    grant: { screen: consent, use: consent.record }
`);
  assert.deepEqual(doc.login.map((e) => e.id), ["identify", "grant", "grant-2"]);
  assert.equal(consentStart(doc.login), 2);
  assert.ok(!toYaml(doc).includes("\nconsent:"));
});

test("passkey, or password plus a second factor", () => {
  let doc = emptyFlow("passkey-totp");
  doc = add(doc, LOGIN, "Identifier");
  doc = updateBody(doc, step(0), { with: { passkey: true } });

  doc = add(doc, LOGIN, "Choose");
  doc = rename(doc, step(1), "credential");
  doc = add(doc, options(1), "Passkey");
  doc = updateBody(doc, option(1, 0), { if: compare("eq", "user.identified_by", "passkey") });
  doc = add(doc, options(1), "Password");
  doc = updateBody(doc, option(1, 1), { if: compare("eq", "user.identified_by", "identifier") });

  doc = add(doc, LOGIN, "Choose");
  doc = rename(doc, step(2), "second-factor");
  doc = updateBody(doc, step(2), {
    if: all(
      compare("ne", "auth.acr", "faye:2fa"),
      any("user.has_totp", "user.mfa_required", compare("has", "host.acr_values", "faye:2fa")),
    ),
  });
  doc = add(doc, options(2), "TOTP");
  doc = updateBody(doc, option(2, 0), { if: "user.has_totp" });
  doc = add(doc, options(2), "Passkey");
  doc = updateBody(doc, option(2, 1), { if: all("user.has_passkey", not(compare("has", "auth.amr", "hwk"))) });
  doc = add(doc, options(2), "Enroll TOTP");
  doc = updateBody(doc, option(2, 2), { if: not(any("user.has_totp", "user.has_passkey")) });

  doc = add(doc, LOGIN, "Add passkey");
  doc = updateBody(doc, step(3), { if: all(not("user.has_passkey"), compare("eq", "auth.acr", "faye:2fa")) });

  doc = add(doc, LOGIN, "Consent");
  doc = rename(doc, step(4), "grant");
  doc = updateBody(doc, step(4), { if: not("client.first_party") });
  sameAsShipped(doc, "passkey-totp.yaml");
});

test("risk hook routes to a second factor", () => {
  const source = shipped("risk.js");
  let doc = emptyFlow("risk-totp");
  doc = setHook(doc, { id: "risk", source, http: ["ip-reputation"], timeoutMs: 500 });
  doc = add(doc, LOGIN, "Identifier");
  doc = updateBody(doc, step(0), { after: "risk" });
  doc = add(doc, LOGIN, "Password");
  doc = add(doc, LOGIN, "Choose");
  doc = rename(doc, step(2), "second-factor");
  doc = updateBody(doc, step(2), { if: compare("eq", "vars.risk", "high") });
  doc = add(doc, options(2), "TOTP");
  doc = updateBody(doc, option(2, 0), { if: "user.has_totp" });
  doc = add(doc, options(2), "Enroll TOTP");
  doc = updateBody(doc, option(2, 1), { if: not("user.has_totp") });
  sameAsShipped(doc, "risk-totp.yaml", { risk: source });
});

test("shipped flows read into the editor and write back unchanged", () => {
  for (const file of ["password-only.yaml", "passkey-totp.yaml", "partner-consent.yaml", "risk-totp.yaml", "acme.yaml"]) {
    const text = shipped(file);
    assert.equal(canonical(toYaml(fromYaml(text))), canonical(text), file);
  }
});

test("drag and drop keeps ids unique and never moves an entry into itself", () => {
  let doc = emptyFlow("moves");
  doc = add(doc, LOGIN, "Identifier");
  doc = add(doc, LOGIN, "Choose");
  doc = add(doc, options(1), "Password");
  doc = add(doc, LOGIN, "Password");
  assert.deepEqual(doc.login.map((e) => e.id), ["identify", "choose", "credential"]);

  // The password option becomes a step after identify
  doc = move(doc, option(1, 0), LOGIN, 1);
  assert.deepEqual(doc.login.map((e) => e.id), ["identify", "password", "choose", "credential"]);
  assert.deepEqual(doc.login[2].body.alternative, []);

  // A step id already in use is renamed when an entry with it moves into the steps
  doc = move(doc, step(3), options(2), 0);
  doc = add(doc, LOGIN, "Password");
  assert.equal(doc.login[3].id, "credential");
  doc = move(doc, step(3), options(2), 1);
  assert.deepEqual(entryAt(doc, step(2))!.body.alternative!.map((e) => e.id), ["credential", "credential-2"]);
  doc = move(doc, option(2, 0), LOGIN, 0);
  assert.equal(doc.login[0].id, "credential");

  // Moving a choose into its own options does nothing
  const before = toYaml(doc);
  doc = move(doc, step(3), options(3), 0);
  assert.equal(toYaml(doc), before);

  // Within a list, the index is the position before the move
  doc = move(doc, step(0), LOGIN, 3);
  assert.deepEqual(doc.login.map((e) => e.id), ["identify", "password", "credential", "choose"]);
  doc = remove(doc, step(0));
  assert.deepEqual(doc.login.map((e) => e.id), ["password", "credential", "choose"]);
});

test("visit keys match the server's path visits", () => {
  const doc = fromYaml(shipped("passkey-totp.yaml"));
  const keys = [...visitKeys(doc).values()];
  assert.ok(keys.includes("credential/password"));
  assert.ok(keys.includes("second-factor/enroll"));
  assert.ok(keys.includes("add-passkey"));
});

test("a diagnostic's line finds its block", () => {
  const text = shipped("passkey-totp.yaml");
  const lineOf = (needle: string) => text.split("\n").findIndex((l) => l.includes(needle)) + 1;
  // `if` of the password option
  assert.equal(locate(text, lineOf("if: { eq: [user.identified_by, identifier] }"), 15), "login/steps.1/alternative.1");
  assert.equal(locate(text, lineOf("add-passkey:"), 5), "login/steps.3");
  assert.equal(locate(text, lineOf("block: enroll-totp"), 11), "login/steps.2/alternative.2");
  assert.equal(locate(text, 1, 1), null);
  const risk = shipped("risk-totp.yaml");
  assert.equal(locate(risk, risk.split("\n").findIndex((l) => l.includes("timeout_ms")) + 1, 5), "hook:risk");
});

test("a hook step from before ADR 0030 opens as the hook of a neighbouring block", () => {
  const doc = fromYaml(`flow: f
version: 1
login:
  steps:
    early: { hook: a }
    identify: { screen: identifier, use: identity.resolve }
    late: { hook: c }
    credential: { hook: d, screen: password, use: password.verify }
`);
  assert.deepEqual(
    doc.login.map((e) => [e.id, e.body.before, e.body.after]),
    [
      ["identify", "a", "c"],
      ["credential", "d", undefined],
    ],
  );
  // c follows a block that already has an after hook, and the next block has a before hook
  assert.throws(
    () =>
      fromYaml(`flow: f
version: 1
login:
  steps:
    identify: { screen: identifier, use: identity.resolve, after: b }
    late: { hook: c }
    credential: { before: d, screen: password, use: password.verify }
`),
    /'login.steps.late' runs hook 'c' on its own/,
  );
  // The risk flow as it was written before
  const old = shipped("risk-totp.yaml").replace("      block: identifier\n      after: risk\n", "      block: identifier\n    risk:\n      hook: risk\n");
  assert.notEqual(old, shipped("risk-totp.yaml"));
  assert.equal(canonical(toYaml(fromYaml(old))), canonical(shipped("risk-totp.yaml")));
});

test("old YAML opens converted only where its meaning survives", () => {
  const open = (steps: string) => () => fromYaml(`flow: f\nversion: 1\nlogin:\n  steps:\n${steps}`);
  // A hook option of a choice, or a hook under a condition, has no block to run around
  assert.throws(open("    c:\n      alternative:\n        a: { hook: h }\n        b: { fail: no }\n"), /runs hook 'h' on its own/);
  assert.throws(open("    x: { if: user.exists, hook: h }\n    y: { fail: no }\n"), /runs hook 'h' on its own/);
  // Never moved under a neighbour's condition, nor after a fail
  const doc = open("    a: { if: user.exists, set: { x: 1 } }\n    h: { hook: h }\n    b: { set: { y: 1 } }\n")();
  assert.deepEqual([doc.login[0].body.after, doc.login[1].body.before], [undefined, "h"]);
  assert.throws(open("    a: { fail: no }\n    h: { hook: h }\n"), /on its own/);
  // A set's hook ran after it
  assert.equal(open("    a: { set: { x: 1 }, hook: h }\n")().login[0].body.after, "h");
  // Keys with no block to go to are refused, not dropped
  assert.throws(open("    g: { retry: 2, steps: { a: { fail: no } } }\n"), /only a block has attempts/);
  assert.throws(open("    i: { screen: identifier }\n"), /a block brings both/);
  assert.throws(open("    p: { screen: otp, use: password.verify }\n"), /shows 'otp', but the Password block shows 'password'/);
  assert.throws(open("    p: { use: consent.record }\n"), /with no screen/);
  // A body that sets vars and calls a handler has no one block to become
  assert.throws(open("    p: { set: { tier: gold }, screen: password, use: password.verify }\n"), /make it a Set step and a block/);
  assert.ok(isLegacy(shipped("password-only.yaml").replace("block: password", "use: password.verify")));
  assert.ok(isLegacy(`${shipped("password-only.yaml")}consent:\n  steps:\n    grant: { block: consent }\n`));
  // Hook declarations and literals are not old keys
  assert.ok(!isLegacy(shipped("risk-totp.yaml")));
  assert.ok(!isLegacy("flow: f\nversion: 1\nlogin:\n  steps:\n    s: { set: { screen: x, use: y } }\n"));
  assert.ok(!isLegacy(shipped("password-only.yaml")));
});

test("the palette offers a block only where it compiles", () => {
  let doc = emptyFlow("f");
  const fits = (name: string, list = LOGIN, at = doc.login.length) => misfit(doc, list, at, block(name).role);
  // An empty flow starts with the Identifier, and nothing else
  assert.equal(fits("Identifier", LOGIN, 0), null);
  assert.equal(fits("Password", LOGIN, 0), "Start with the Identifier");
  doc = add(doc, LOGIN, "Identifier");
  assert.equal(fits("Identifier", LOGIN, 0), "The flow already starts with the Identifier");
  assert.equal(fits("Password", LOGIN, 0), "The Identifier stays the first step");
  assert.equal(fits("Password"), null);
  // A choice's options are one credential block, or Fail
  doc = add(doc, LOGIN, "Choose");
  for (const name of ["Password", "Passkey", "TOTP", "Enroll TOTP", "Add passkey", "Fail"]) assert.equal(fits(name, options(1), 0), null, name);
  for (const name of ["Group", "Set", "Choose", "Consent"]) assert.notEqual(fits(name, options(1), 0), null, name);
  // Consent is a step of its own
  doc = add(doc, LOGIN, "Group");
  const group: ListRef = { section: "login", owner: [["steps", 2]], kind: "steps" };
  assert.equal(fits("Consent", group, 0), "Consent is a step of its own");
  // With the password on the identifier screen, no Password block
  doc = updateBody(doc, step(0), { with: { password: true } });
  assert.equal(fits("Password"), "The Identifier already asks for the password");
  assert.equal(misplaced(doc), 0);
  doc = add(doc, options(1), "Password");
  assert.equal(misplaced(doc), 1);
  // Moving the Identifier from first place is a misplacement too
  assert.equal(misplaced(move(doc, step(0), LOGIN, 2)), 2);
});

test("removing an option that leaves one makes the choice that option", () => {
  let doc = fromYaml(shipped("passkey-totp.yaml"));
  doc = removeEntry(doc, option(1, 1));
  assert.deepEqual(doc.login[1].body, { if: { eq: ["user.identified_by", "passkey"] }, block: "passkey" });
  // With three options, one goes and the choice stays
  doc = removeEntry(doc, option(2, 0));
  assert.equal(doc.login[2].body.alternative?.length, 2);
});

test("credentials stay before Consent, which runs once signed in", () => {
  let doc = emptyFlow("f");
  doc = add(doc, LOGIN, "Identifier");
  doc = add(doc, LOGIN, "Password");
  assert.equal(misfit(doc, LOGIN, 1, "consent"), "Consent comes after every credential step");
  assert.equal(misfit(doc, LOGIN, 2, "consent"), null);
  doc = add(doc, LOGIN, "Consent");
  doc = add(doc, LOGIN, "Set");
  assert.equal(misfit(doc, LOGIN, 2, "credential"), null);
  assert.equal(misfit(doc, LOGIN, 3, "credential"), "Steps after Consent run once signed in; put credentials before it");
  assert.equal(misplaced(doc), 0);
  assert.equal(misplaced(move(doc, step(1), LOGIN, 4)), 1);
});

test("the identifier asks for the password in its password view", () => {
  let doc = emptyFlow("one-screen");
  doc = add(doc, LOGIN, "Identifier");
  doc = updateBody(doc, step(0), { with: { password: true } });
  assert.equal(
    toYaml(doc),
    `flow: one-screen
version: 1
login:
  steps:
    identify:
      block: identifier
      with: { password: true }
`,
  );
  // Settings that equal the block's default are not written
  assert.deepEqual(withSetting(doc.login[0].body, "attempts", 3), { password: true });
  assert.deepEqual(withSetting(doc.login[0].body, "attempts", 5), { password: true, attempts: 5 });
  assert.equal(setting(doc.login[0].body, "attempts"), 3);
});

test("a tenant's block defaults replace the block's own, and the flow still wins", () => {
  const credential = { block: "password" };
  const tenant = { password: { attempts: 5 } };
  assert.equal(setting(credential, "attempts", tenant), 5);
  assert.equal(setting({ ...credential, with: { attempts: 2 } }, "attempts", tenant), 2);
  // Setting the tenant's default writes nothing; the block's own default is a change then
  assert.equal(withSetting(credential, "attempts", 5, tenant), undefined);
  assert.deepEqual(withSetting(credential, "attempts", 3, tenant), { attempts: 3 });
});

test("a step from before ADR 0030 opens as its handler's block, with the settings it meant", () => {
  const doc = fromYaml(`flow: f
version: 1
login:
  steps:
    identify: { screen: { type: identifier, passkey: true }, use: identity.resolve }
    credential: { retry: 3, screen: password, use: password.verify }
    once: { screen: otp, use: totp.verify }
    add: { screen: { type: passkey, register: true, path: /challenge/add }, use: webauthn.register }
`);
  assert.deepEqual(
    doc.login.map((e) => e.body),
    [
      { block: "identifier", with: { passkey: true } },
      { block: "password" },
      { block: "totp", with: { attempts: 1 } },
      { block: "add-passkey", path: "/challenge/add" },
    ],
  );
  assert.throws(() => fromYaml("flow: f\nversion: 1\nlogin:\n  steps:\n    x: { use: custom.check }\n"), /uses handler 'custom.check', which no block has/);
});

test("the palette offers exactly the blocks of the contract, with its defaults (conformance/blocks.yaml)", () => {
  const contract = parse(readFileSync(new URL("../../../../conformance/blocks.yaml", import.meta.url), "utf8")) as Record<
    string,
    { title: string; handler: string; screen: string; role: string; settings?: Record<string, { default: Literal }> }
  >;
  const palette = Object.fromEntries(
    PALETTE.filter((b) => b.id !== undefined).map((b) => [b.id, { title: b.name, handler: b.use, screen: b.screen, role: b.role, settings: b.settings }]),
  );
  const expected = Object.fromEntries(
    Object.entries(contract).map(([id, b]) => [
      id,
      { title: b.title, handler: b.handler, screen: b.screen, role: b.role, settings: Object.fromEntries(Object.entries(b.settings ?? {}).map(([k, v]) => [k, v.default])) },
    ]),
  );
  assert.deepEqual(palette, expected);
});

test("line diff", () => {
  assert.deepEqual(diffLines("a\nb\nc", "a\nc\nd").map((l) => `${l.kind} ${l.text}`), [
    "same a",
    "del b",
    "same c",
    "add d",
  ]);
});

test("conditions read with their grouping", () => {
  assert.equal(describe(all("a.b", any("c.d", "e.f"))), "a.b and (c.d or e.f)");
});
