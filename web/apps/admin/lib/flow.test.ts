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
  move,
  remove,
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

test("the consent screen alone starts consent, as in the compiler", () => {
  const steps = fromYaml(`flow: f
version: 1
login:
  steps:
    identify: { screen: identifier, use: identity.resolve, set: { use: consent.record } }
    ask: { screen: consent }
`).login;
  assert.equal(consentStart(steps), 1);
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
    audit: { hook: h }
    grant: { screen: consent, use: consent.record }
`),
    FlowShapeError,
  );
});

test("a consent section from before ADR 0025 opens as steps at the end", () => {
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
  doc = updateBody(doc, step(0), { screen: { type: "identifier", opts: { passkey: true } } });

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
  sameAsShipped(doc, "passkey-totp.yaml");
});

test("risk hook routes to a second factor", () => {
  const source = shipped("risk.js");
  let doc = emptyFlow("risk-totp");
  doc = setHook(doc, { id: "risk", source, http: ["ip-reputation"], timeoutMs: 500 });
  doc = add(doc, LOGIN, "Identifier");
  doc = add(doc, LOGIN, "Hook");
  doc = rename(doc, step(1), "risk");
  doc = updateBody(doc, step(1), { hook: "risk" });
  doc = add(doc, LOGIN, "Password");
  doc = add(doc, LOGIN, "Choose");
  doc = rename(doc, step(3), "second-factor");
  doc = updateBody(doc, step(3), { if: compare("eq", "vars.risk", "high") });
  doc = add(doc, options(3), "TOTP");
  doc = updateBody(doc, option(3, 0), { if: "user.has_totp" });
  doc = add(doc, options(3), "Enroll TOTP");
  doc = updateBody(doc, option(3, 1), { if: not("user.has_totp") });
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
  assert.equal(locate(text, lineOf("use: totp.enroll"), 11), "login/steps.2/alternative.2");
  assert.equal(locate(text, 1, 1), null);
  const risk = shipped("risk-totp.yaml");
  assert.equal(locate(risk, risk.split("\n").findIndex((l) => l.includes("timeout_ms")) + 1, 5), "hook:risk");
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
