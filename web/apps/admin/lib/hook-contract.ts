/** What a hook sees, for completions in the hook editor (see docs/roadmap/hooks.md). */
export type Member = { label: string; detail: string; children?: Record<string, Member> };

const leaf = (detail: string): Member => ({ label: "", detail });

export const HOOK_GLOBALS: Record<string, Member> = {
  ctx: {
    label: "ctx",
    detail: "read only, deep frozen",
    children: {
      request: { label: "request", detail: "the browser's request", children: { ip: leaf("string"), user_agent: leaf("string") } },
      client: { label: "client", detail: "the OAuth client", children: { client_id: leaf("string"), first_party: leaf("boolean"), tenant: leaf("string") } },
      hydra: {
        label: "hydra",
        detail: "the login request",
        children: { acr_values: leaf("string[]"), login_hint: leaf("string"), requested_scope: leaf("string[]"), skip: leaf("boolean") },
      },
      tenant: { label: "tenant", detail: "tenant settings", children: { mfa_required: leaf("boolean") } },
      user: {
        label: "user",
        detail: "set by identity.resolve",
        children: {
          exists: leaf("boolean"),
          identified_by: leaf("'identifier' | 'passkey'"),
          has_password: leaf("boolean"),
          has_passkey: leaf("boolean"),
          has_totp: leaf("boolean"),
          mfa_required: leaf("boolean"),
        },
      },
      vars: { label: "vars", detail: "set by set and by hooks" },
      options: { label: "options", detail: "string[]: enabled option ids, for the hook of a choose" },
    },
  },
  cap: {
    label: "cap",
    detail: "what the hook may do besides return",
    children: {
      http: { label: "http", detail: "named egress targets", children: { getJson: leaf("(target, query) => Promise<any>") } },
      log: { label: "log", detail: "lines shown in test runs", children: { info: leaf("(...args) => void") } },
    },
  },
};

export const HOOK_TEMPLATE = `// Return { set: { name: value } }, { fail: "reason" }, { pick: "option" } (in a choose), or {}.
export default async function (ctx, cap) {
  return {}
}
`;

export const SAMPLE_CTX = {
  request: { ip: "203.0.113.7", user_agent: "Mozilla/5.0" },
  client: { client_id: "demo", first_party: true },
  hydra: { acr_values: [], skip: false },
  tenant: { mfa_required: false },
  user: { exists: true, identified_by: "identifier", has_password: true, has_passkey: false, has_totp: true, mfa_required: false },
  vars: {},
};
