// Registers the OAuth clients in Hydra. Safe to rerun. Every client belongs to one of the two
// tenants (metadata.tenant), whose flow it runs and whose user pool it signs in:
//   acme   Acme Web, Acme Docs, Acme Shop (first party); Acme Partner Portal, Acme Analytics (third party)
//   bank   Bank Online, Bank Mobile, Bank Advisor Desk, Bank Business, Bank Investments (first party)
// Hydra generates each application's id and secret. Each tenant also gets preview-<tenant>, a public
// client that runs the tenant's draft (metadata.preview), for the Admin UI's Preview.
// Runs in the demo service before the demo client starts (compose), or from deploy/dev.sh.
import { appendFileSync, chmodSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const admin = process.env.HYDRA_ADMIN_URL ?? "http://localhost:4445";
const redirect = process.env.DEMO_REDIRECT_URI ?? "http://localhost:3001/callback";
// Outside the repo by default; compose sets SECRETS_DIR to its secrets volume
const secrets =
  process.env.SECRETS_DIR ?? join(process.env.XDG_STATE_HOME ?? join(homedir(), ".local", "state"), "faye", "secrets");

for (;;) {
  try {
    if ((await fetch(`${admin}/health/ready`)).ok) break;
  } catch {
    // not up yet
  }
  await new Promise((resolve) => setTimeout(resolve, 1000));
}

async function hydra(path, init) {
  const res = await fetch(`${admin}${path}`, init);
  if (!res.ok && res.status !== 404) throw new Error(`${init?.method ?? "GET"} ${path}: ${res.status} ${await res.text()}`);
  return res;
}

function client(name, tenant, firstParty, preview) {
  return {
    client_name: name,
    grant_types: ["authorization_code", "refresh_token"],
    response_types: ["code"],
    scope: "openid profile email offline_access",
    redirect_uris: [redirect],
    token_endpoint_auth_method: preview ? "none" : "client_secret_basic",
    metadata: { tenant, first_party: firstParty, preview },
  };
}

const post = (body) =>
  hydra("/admin/clients", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });

// The Admin UI's Preview opens the demo client with these. Faye refuses them unless
// FAYE_PREVIEW_ENABLED is true.
for (const tenant of ["acme", "bank"]) {
  const id = `preview-${tenant}`;
  if ((await hydra(`/admin/clients/${id}`)).ok) {
    console.log(`client ${id} already registered`);
    continue;
  }
  await post({ client_id: id, ...client(`Preview: ${tenant}`, tenant, true, true) });
  console.log(`client ${id} registered`);
}

// The ten applications. Hydra generates each client's id and secret (no client_id or client_secret
// is sent); its one response with the secret goes to the secrets directory, which the demo client
// (their relying party) reads and Faye never does; never to this log. Skipped when a client of that
// name exists.
mkdirSync(secrets, { recursive: true, mode: 0o700 });
chmodSync(secrets, 0o700);
const APPLICATIONS = [
  ["Acme Web", "acme", true],
  ["Acme Docs", "acme", true],
  ["Acme Shop", "acme", true],
  ["Acme Partner Portal", "acme", false],
  ["Acme Analytics", "acme", false],
  ["Bank Online", "bank", true],
  ["Bank Mobile", "bank", true],
  ["Bank Advisor Desk", "bank", true],
  ["Bank Business", "bank", false],
  ["Bank Investments", "bank", false],
];
for (const [name, tenant, firstParty] of APPLICATIONS) {
  const existing = await (await hydra(`/admin/clients?client_name=${encodeURIComponent(name)}`)).json();
  if (existing.length > 0) {
    console.log(`client "${name}" already registered`);
    continue;
  }
  const registered = await (await post(client(name, tenant, firstParty, false))).json();
  appendFileSync(join(secrets, "clients.jsonl"), JSON.stringify(registered) + "\n", { mode: 0o600 });
  console.log(`client "${name}" registered as ${registered.client_id}`);
}
