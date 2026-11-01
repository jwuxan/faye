import assert from "node:assert/strict";
import { test } from "node:test";
import { highlightLines } from "./highlight.ts";

test("highlightLines keeps every line and its text", () => {
  const yaml = "# a flow\nflow: acme\nlogin:\n  steps: []\n";
  const lines = highlightLines(yaml);
  assert.deepEqual(
    lines.map((l) => l.map((t) => t.text).join("")),
    yaml.split("\n"),
  );
});

test("highlightLines marks keys, comments and quoted strings", () => {
  const [comment, entry] = highlightLines('# note\nname: "Acme"');
  assert.match(comment[0].cls, /tok-comment/);
  assert.match(entry.find((t) => t.text === "name")!.cls, /tok-propertyName/);
  assert.match(entry.find((t) => t.text === '"Acme"')!.cls, /tok-string/);
});
