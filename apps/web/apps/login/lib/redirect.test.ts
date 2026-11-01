/**
 * F15 (ADR 0007), the Login UI's part: whatever string the server's redirectTo holds, the browser
 * is only ever sent to an absolute http(s) URL with a host, and never to a script or data URL.
 * fast-check runs a fixed number of runs from a fixed seed, so a failure reproduces.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import fc from "fast-check";
import { isNavigable } from "./redirect.ts";

const RUNS = { numRuns: 5000, seed: 7 };
const schema = JSON.parse(readFileSync(new URL("../../../../../src/schema/screen.schema.json", import.meta.url), "utf8"));
const protocol = new RegExp(schema.$defs.RedirectResponse.properties.redirectTo.pattern);

const hostile = [
  "javascript:alert(1)",
  " javascript:alert(1)",
  "JaVaScRiPt:alert(1)",
  "java\tscript:alert(1)",
  "data:text/html,<script>alert(1)</script>",
  "vbscript:x",
  "//evil.example/",
  "/relative",
  "https:/missing-slash",
  "https://",
  "https://ok.example/\r\nSet-Cookie: x",
  "https://ok.example/\u0000",
  "https://ok.example/ a",
  "",
];

test("hostile redirects are refused", () => {
  for (const url of hostile) assert.equal(isNavigable(url), false, JSON.stringify(url));
});

test("whatever passes is an http(s) URL with a host, and matches the protocol's pattern", () => {
  const anything = fc.oneof(
    fc.string(),
    fc.webUrl({ validSchemes: ["http", "https"], withQueryParameters: true, withFragments: true }),
    fc.tuple(fc.constantFrom("https://", "http://", "javascript:", "data:", "HTTPS://", " https://"), fc.string()).map(([a, b]) => a + b),
  );
  fc.assert(
    fc.property(anything, (url) => {
      if (!isNavigable(url)) return;
      const parsed = new URL(url);
      assert.ok(parsed.protocol === "https:" || parsed.protocol === "http:", url);
      assert.notEqual(parsed.hostname, "");
      assert.ok(protocol.test(url), `the server's schema would refuse ${JSON.stringify(url)}`);
    }),
    RUNS,
  );
});

test("real redirects pass", () => {
  fc.assert(
    fc.property(fc.webUrl({ validSchemes: ["https"], withQueryParameters: true }), (url) => {
      assert.equal(isNavigable(url), true, url);
    }),
    RUNS,
  );
});
