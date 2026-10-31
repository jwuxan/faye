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
