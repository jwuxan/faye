/** ADR 0035: the Time limits section's conversions and checks, which mirror the admin API's. */
import assert from "node:assert/strict";
import { test } from "node:test";
import { fromMinutes, kindLabel, limitProblems, toMinutes, withLimit } from "./flow-settings.ts";

const view = { kinds: ["authenticate", "consent"], maxSeconds: 1800, minSeconds: 60 };

test("minutes in the fields are seconds in the API, and empty is unset", () => {
  assert.equal(fromMinutes("10"), 600);
  assert.equal(fromMinutes(""), undefined);
  assert.equal(fromMinutes("1.5"), undefined);
  assert.equal(toMinutes(600), "10");
  assert.equal(toMinutes(undefined), "");
});

test("clearing a kind's last limit drops the kind, as the server does", () => {
  let s = withLimit({}, "authenticate", "lifetimeSeconds", 600);
  assert.deepEqual(s, { authenticate: { lifetimeSeconds: 600 } });
  s = withLimit(s, "authenticate", "idleTimeoutSeconds", 300);
  assert.deepEqual(s, { authenticate: { lifetimeSeconds: 600, idleTimeoutSeconds: 300 } });
  s = withLimit(withLimit(s, "authenticate", "lifetimeSeconds", undefined), "authenticate", "idleTimeoutSeconds", undefined);
  assert.deepEqual(s, {});
});

test("limits outside one minute and the deployment's maximum, or an idle timeout over the time limit, are problems", () => {
  assert.deepEqual(limitProblems({ lifetimeSeconds: 600, idleTimeoutSeconds: 300 }, view), {});
  assert.deepEqual(limitProblems({}, view), {});
  assert.ok(limitProblems({ lifetimeSeconds: 1860 }, view).lifetimeSeconds);
  assert.ok(limitProblems({ lifetimeSeconds: 0 }, view).lifetimeSeconds);
  assert.equal(limitProblems({ lifetimeSeconds: 600, idleTimeoutSeconds: 660 }, view).idleTimeoutSeconds, "No longer than the time limit");
  assert.ok(limitProblems({ idleTimeoutSeconds: 1860 }, view).idleTimeoutSeconds);
});

test("a kind the console has no label for shows under its own name", () => {
  assert.equal(kindLabel("authenticate").title, "Sign in");
  assert.equal(kindLabel("register").title, "register");
});
