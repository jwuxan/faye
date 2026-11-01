/**
 * A stand in for the Faye server's screen API, for the Login UI's browser tests: it serves the
 * screen a flow id names and records what the browser submits, so a test checks what the Login UI
 * renders and sends, not what the engine decides (the conformance vectors and smoke.sh do that).
 *
 * A flow id is `f_<scenario>_<anything>`; each test uses its own, so tests running in parallel
 * share this one server. GET /__submissions/<flowId> returns the payloads submitted for a flow.
 *
 * Run by Node directly (type stripping): node e2e/stub-faye.ts <port>
 */
import type { Branding, Screen, ScreenResponse } from "@faye/contract";
import { createServer } from "node:http";

const scenarios: Record<string, { screen: Screen; branding?: Branding }> = {
  // The tenant offers "keep me signed in", and has a name for the footer
  remember: { screen: { type: "identifier", remember: true }, branding: { name: "Acme" } },
  // Nothing turned on, no branding at all
  plain: { screen: { type: "identifier" } },
  // Identifier and password in one form, with every ADR 0033 option on
  idpw: { screen: { type: "identifier", password: true, remember: true }, branding: { name: "Acme", showPassword: true } },
  password: { screen: { type: "password", identifier: "ada@example.com" }, branding: { name: "Acme", showPassword: true } },
  nopassword: { screen: { type: "password", identifier: "ada@example.com" }, branding: { name: "Acme" } },
  signup: { screen: { type: "identifier" }, branding: { name: "Acme", signUp: true } },
};

const submissions = new Map<string, unknown[]>();

function scenarioOf(flowId: string) {
  return scenarios[/^f_([a-z]+)_/.exec(flowId)?.[1] ?? ""];
}

function response(flowId: string, step: number): ScreenResponse | null {
  const scenario = scenarioOf(flowId);
  if (!scenario) return null;
  // After an answer, the password screen of the same tenant: shows the footer on a later screen
  const screen: Screen = step === 0 ? scenario.screen : { type: "password", identifier: "ada@example.com" };
  return { flowId, nonce: `n${step}`, path: "/login", screen, ...(scenario.branding ? { branding: scenario.branding } : {}) };
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
    const body = response(flowId, (submissions.get(flowId) ?? []).length);
    return body ? json(res, 200, body) : json(res, 404, { error: "flow_not_found" });
  }
  if (req.method === "POST" && flow[2]) {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      const list = submissions.get(flowId) ?? [];
      list.push((JSON.parse(raw) as { payload: unknown }).payload);
      submissions.set(flowId, list);
      const body = response(flowId, list.length);
      if (body) json(res, 200, body);
      else json(res, 404, { error: "flow_not_found" });
    });
    return;
  }
  json(res, 405, { error: "invalid_submission" });
});

server.listen(Number(process.argv[2] ?? 18080), "127.0.0.1");
