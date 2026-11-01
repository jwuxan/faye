import assert from "node:assert/strict";
import { test } from "node:test";
import { yamlCompletions } from "./yaml-complete.ts";

const FLOW = `flow: acme
version: 1
hooks:
  risk:
    file: risk.js
login:
  steps:
    identify:
      block: identifier
      after: risk
    second-factor:
      alternative:
        totp:
          block: totp
`;

/** Completions with the cursor where `|` is. */
function at(doc: string, explicit = false) {
  const pos = doc.indexOf("|");
  const r = yamlCompletions(doc.replace("|", ""), pos, explicit);
  return r ? { from: r.from, labels: r.options.map((o) => o.label) } : null;
}

test("top level keys, without the ones already there", () => {
  const r = at(`flow: acme\nve|\n`);
  assert.deepEqual(r?.labels, ["version", "hooks", "login"]);
  assert.equal(r?.from, "flow: acme\n".length);
});

test("a step's keys, without the ones it has", () => {
  const r = at(FLOW.replace("      after: risk\n", "      after: risk\n      i|\n"));
  assert.ok(r?.labels.includes("if"));
  assert.ok(!r?.labels.includes("block"));
  assert.ok(!r?.labels.includes("after"));
});

test("an option's keys inside an alternative", () => {
  const r = at(FLOW.replace("          block: totp\n", "          block: totp\n          f|\n"));
  assert.ok(r?.labels.includes("fail"));
  assert.ok(!r?.labels.includes("block"));
});

test("block ids after block:", () => {
  const r = at(FLOW.replace("block: totp", "block: pa|"));
  assert.ok(r?.labels.includes("password"));
  assert.ok(r?.labels.includes("passkey"));
});

test("the flow's hooks after after:, before: and pick:", () => {
  assert.deepEqual(at(FLOW.replace("after: risk", "after: r|"))?.labels, ["risk"]);
  assert.deepEqual(at(FLOW.replace("      alternative:", "      pick: |\n      alternative:"), true)?.labels, ["risk"]);
});

test("a block's settings under with:", () => {
  const r = at(FLOW.replace("      after: risk\n", "      after: risk\n      with:\n        pa|\n"));
  assert.ok(r?.labels.includes("passkey"));
  assert.ok(r?.labels.includes("attempts"));
});

test("paths and operators in a condition", () => {
  const r = at(FLOW.replace("          block: totp", "          if: { not: user.has|"));
  assert.ok(r?.labels.includes("user.has_totp"));
  assert.ok(r?.labels.includes("eq"));
});

test("a hook's keys", () => {
  const r = at(FLOW.replace("    file: risk.js\n", "    file: risk.js\n    t|\n"));
  assert.deepEqual(r?.labels, ["source", "capabilities", "timeout_ms"]);
});

test("nothing in a comment, or on an empty line unless asked", () => {
  assert.equal(at(`# fl|`), null);
  assert.equal(at(`flow: acme\n|`), null);
  assert.ok(at(`flow: acme\n|`, true)?.labels.includes("login"));
});
