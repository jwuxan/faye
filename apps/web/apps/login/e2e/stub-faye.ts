/**
 * A stand in for the Faye server's screen API, for the Login UI's browser tests: it serves the
 * screen a flow id names and records what the browser submits, so a test checks what the Login UI
 * renders and sends, not what the engine decides (the conformance vectors and smoke.sh do that).
 *
 * A flow id is `f_<scenario>_<anything>`; each test uses its own, so tests running in parallel
 * share this one server. GET /__submissions/<flowId> returns the payloads submitted for a flow; a
 * screen action (ADR 0032) is recorded as `{ action, payload }` and answered with the same screen,
 * as `acted` shows it.
 *
 * Run by Node directly (type stripping): node e2e/stub-faye.ts <port>
 */
import type { Branding, Screen, ScreenResponse } from "@faye/contract";
import { createServer } from "node:http";

const scenarios: Record<string, { screen: Screen; acted?: Screen; branding?: Branding }> = {
  // The tenant offers "keep me signed in", and has a name for the footer
  remember: { screen: { type: "identifier", remember: true }, branding: { name: "Acme" } },
  // Nothing turned on, no branding at all
  plain: { screen: { type: "identifier" } },
  // Identifier and password in one form, with every ADR 0033 option on
  idpw: { screen: { type: "identifier", password: true, remember: true }, branding: { name: "Acme", showPassword: true } },
  password: { screen: { type: "password", identifier: "ada@example.com" }, branding: { name: "Acme", showPassword: true } },
  nopassword: { screen: { type: "password", identifier: "ada@example.com" }, branding: { name: "Acme" } },
  signup: { screen: { type: "identifier" }, branding: { name: "Acme", signUp: true } },
  // A code Faye emailed, which may be resent at once (ADR 0032)
  emailcode: {
    screen: { type: "code_sent", identifier: "ada@example.com", channel: "email", to: "a•••@example.com", resendIn: 0, expiresIn: 300 },
    acted: { type: "code_sent", identifier: "ada@example.com", channel: "email", to: "a•••@example.com", resendIn: 30, expiresIn: 300 },
  },
  // QR code sign in on the waiting device: approved at the first poll (ADR 0032)
  qr: {
    screen: { type: "qr", url: "http://localhost:3000/approve/tok", number: 42, expiresIn: 120, status: "pending" },
    acted: { type: "qr", url: "http://localhost:3000/approve/tok", number: 42, expiresIn: 120, status: "approved" },
  },
  // And on the phone: pick the number the other device shows
  qrapprove: {
    screen: { type: "qr_approve", client: { name: "Acme Web" }, device: "Chrome on macOS", numbers: [17, 42, 73] },
  },
  // Adding a phone: nothing sent until the person asks
  phone: {
    screen: { type: "phone_enroll", identifier: "ada@example.com" },
    acted: { type: "phone_enroll", identifier: "ada@example.com", to: "••• ••• 4821", resendIn: 30, expiresIn: 300 },
  },
  // The step requires a phone: no "Not now" (ADR 0038)
  phonerequired: { screen: { type: "phone_enroll", identifier: "ada@example.com", required: true } },
};

const submissions = new Map<string, unknown[]>();

function scenarioOf(flowId: string) {
  return scenarios[/^f_([a-z]+)_/.exec(flowId)?.[1] ?? ""];
}

function response(flowId: string, step: number, acted = false): ScreenResponse | null {
  const scenario = scenarioOf(flowId);
  if (!scenario) return null;
  // After an answer, the password screen of the same tenant: shows the footer on a later screen
  const first = acted && scenario.acted ? scenario.acted : scenario.screen;
  const screen: Screen = step === 0 ? first : { type: "password", identifier: "ada@example.com" };
  return { flowId, nonce: `n${step}`, path: "/login", screen, ...(scenario.branding ? { branding: scenario.branding } : {}) };
}

/** How many answers a flow took; actions do not move it. */
function answers(flowId: string): number {
  return (submissions.get(flowId) ?? []).filter((s) => !(typeof s === "object" && s !== null && "action" in s)).length;
}

function json(res: import("node:http").ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://stub");
  const recorded = /^\/__submissions\/([^/]+)$/.exec(url.pathname);
  if (req.method === "GET" && recorded) return json(res, 200, submissions.get(recorded[1]) ?? []);
  const flow = /^\/api\/flows\/([^/]+)(\/submit)?$/.exec(url.pathname);
  if (!flow) return json(res, 404, { error: "flow_not_found" });
  const flowId = decodeURIComponent(flow[1]);
  if (req.method === "GET" && !flow[2]) {
    const body = response(flowId, answers(flowId));
    return body ? json(res, 200, body) : json(res, 404, { error: "flow_not_found" });
  }
  if (req.method === "POST" && flow[2]) {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      const list = submissions.get(flowId) ?? [];
      const { action, payload } = JSON.parse(raw) as { action: string; payload: unknown };
      list.push(action === "submit" ? payload : { action, payload });
      submissions.set(flowId, list);
      const body = response(flowId, answers(flowId), action !== "submit");
      if (body) json(res, 200, body);
      else json(res, 404, { error: "flow_not_found" });
    });
    return;
  }
  json(res, 405, { error: "invalid_submission" });
});

server.listen(Number(process.argv[2] ?? 18080), "127.0.0.1");
