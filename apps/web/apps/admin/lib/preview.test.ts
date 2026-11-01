/** M6: the preview panel's TOTP helper and how it follows a live trace. */
import assert from "node:assert/strict";
import { test } from "node:test";
import { totp } from "./totp.ts";
import { follow, type LiveRun, type Recorded } from "./trace.ts";

test("totp matches the RFC 6238 SHA-1 test vectors, truncated to six digits", async () => {
  // The RFC's secret is the ASCII "12345678901234567890"
  const secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
  assert.equal(await totp(secret, 59_000), "287082");
  assert.equal(await totp(secret, 1_111_111_109_000), "081804");
  assert.equal(await totp(secret, 2_000_000_000_000), "279037");
});

test("a live run collects visit keys and knows which block waits on a screen", () => {
  const events: Recorded["event"][] = [
    { event: "start", id: "sha256:x", outcome: "login" },
    { event: "step", id: "identify" },
    { event: "screen", id: "identifier" },
    { event: "call", id: "identity.resolve", outcome: "ok" },
    { event: "step", id: "credential" },
    { event: "option", id: "password", outcome: "only" },
    { event: "screen", id: "password" },
  ];
  let run: LiveRun | null = null;
  events.forEach((event, i) => (run = follow(run, { seq: i + 1, tenantId: "t", flowId: "f_1", at: 0, event })));
  assert.deepEqual(run!.visits, ["identify", "credential", "credential/password"]);
  assert.equal(run!.waiting, "credential/password");

  run = follow(run, { seq: 8, tenantId: "t", flowId: "f_1", at: 0, event: { event: "end", outcome: "succeed", amr: ["pwd"], acr: "faye:1fa" } });
  assert.equal(run.waiting, null);
  assert.equal(run.end?.acr, "faye:1fa");

  // The next run starts over
  run = follow(run, { seq: 9, tenantId: "t", flowId: "f_2", at: 0, event: events[0] });
  assert.deepEqual(run.visits, []);
  assert.equal(run.events.length, 1);
});

test("the consent run after a sign in continues it; another run starts over", () => {
  const at = (seq: number, flowId: string, event: Recorded["event"]): Recorded => ({ seq, tenantId: "t", flowId, at: 0, event });
  let run: LiveRun | null = null;
  for (const r of [
    at(1, "f_1", { event: "start", id: "sha256:x", outcome: "login" }),
    at(2, "f_1", { event: "step", id: "credential" }),
    at(3, "f_1", { event: "end", outcome: "succeed", amr: ["pwd"], acr: "faye:1fa" }),
    at(4, "f_2", { event: "start", id: "sha256:x", outcome: "consent" }),
    at(5, "f_2", { event: "step", id: "grant" }),
    at(6, "f_2", { event: "screen", id: "consent" }),
  ]) run = follow(run, r);
  assert.deepEqual(run!.visits, ["credential", "grant"]);
  assert.equal(run!.waiting, "grant");
  assert.equal(run!.end, null);
  assert.deepEqual(run!.signIn, { flowId: "f_1", end: { event: "end", outcome: "succeed", amr: ["pwd"], acr: "faye:1fa" } });
  assert.equal(run!.events.length, 6);

  run = follow(run, at(7, "f_2", { event: "end", outcome: "succeed" }));
  assert.equal(run.end?.outcome, "succeed");
  assert.equal(run.signIn?.flowId, "f_1");

  // A consent run on another version, or after a sign in that failed, is a run of its own
  run = follow(run, at(8, "f_3", { event: "start", id: "sha256:y", outcome: "consent" }));
  assert.deepEqual(run.visits, []);
  assert.equal(run.signIn, undefined);
  let failed: LiveRun | null = null;
  failed = follow(failed, at(9, "f_4", { event: "start", id: "sha256:x", outcome: "login" }));
  failed = follow(failed, at(10, "f_4", { event: "end", outcome: "fail" }));
  failed = follow(failed, at(11, "f_5", { event: "start", id: "sha256:x", outcome: "consent" }));
  assert.equal(failed.signIn, undefined);
  assert.equal(failed.events.length, 1);
});
