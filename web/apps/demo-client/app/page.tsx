import { Alert, AlertDescription, AlertTitle } from "@faye/ui/components/alert";
import { Button } from "@faye/ui/components/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@faye/ui/components/card";
import { CopyButton } from "@faye/ui/components/copy-button";
import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import { client, clients, decodeJwt, ID_TOKEN_COOKIE } from "../lib/oidc";
import { Json } from "./json";
import { describe, matching, previewRun } from "../lib/preview";

/** Claims that say who signed in, and how. */
const IDENTITY = ["sub", "name", "email", "amr", "acr", "auth_time"];

export default async function Home({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const { error, error_description: description, client: selected } = await searchParams;
  // A link that names a client (Try sign in in the Admin UI) signs in straight away, without the panel
  if (!error && selected && client(selected) && !(await cookies()).get(ID_TOKEN_COOKIE)) {
    redirect(`/login?${new URLSearchParams({ client: selected })}`);
  }
  // A failed sign in shows only why; the callback has already cleared any earlier ID token
  if (error) {
    return (
      <>
        <Alert variant="destructive">
          <AlertTitle className="font-mono">{error}</AlertTitle>
          {description && <AlertDescription>{description}</AlertDescription>}
        </Alert>
        <Button asChild variant="outline">
          <Link href="/">Try again</Link>
        </Button>
      </>
    );
  }
  const idToken = (await cookies()).get(ID_TOKEN_COOKIE)?.value;
  const token = idToken ? decodeJwt(idToken) : null;
  const flowId = token?.claims.faye_flow_id;
  const run = typeof flowId === "string" ? await previewRun(flowId) : null;

  // Signed in: only who signed in, the ID token and a way out; the launch panel is for signed out visitors
  if (idToken && token) {
    return (
      <>
        <Card>
          <CardHeader>
            <CardTitle>Signed in</CardTitle>
            <CardDescription>The identity in the ID token from the token endpoint.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
              {IDENTITY.map((name) => (
                <div key={name} className="contents">
                  <dt className="text-muted-foreground">{name}</dt>
                  <dd className="min-w-0 font-mono break-all" data-claim={name}>
                    {token.claims[name] === undefined ? "—" : JSON.stringify(token.claims[name])}
                  </dd>
                </div>
              ))}
            </dl>
            {/* A POST, so a link prefetch or crawler never signs anyone out */}
            <form action="/logout" method="post">
              <Button type="submit" variant="outline">Sign out</Button>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>ID token</CardTitle>
            <CardDescription>As issued, then decoded without verification.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            <div className="relative min-w-0">
              <pre className="overflow-x-auto whitespace-pre-wrap break-all rounded-md bg-muted p-4 pr-12 font-mono text-xs" id="id-token">
                {idToken}
              </pre>
              <CopyButton value={idToken} label="Copy ID token" className="absolute top-2 right-2" />
            </div>
            <div className="relative min-w-0">
              <Json value={token.claims} className="overflow-x-auto rounded-md bg-muted p-4 pr-12 font-mono text-xs" id="claims" />
              <CopyButton value={JSON.stringify(token.claims, null, 2)} label="Copy claims" className="absolute top-2 right-2" />
            </div>
          </CardContent>
        </Card>

        {typeof flowId === "string" && <PreviewResult run={run} claims={token.claims} />}
      </>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Faye demo client</CardTitle>
        <CardDescription>Signs in through Hydra with authorization code and PKCE.</CardDescription>
      </CardHeader>
      <CardContent>
        {/* A plain GET form: /login is a route handler that redirects to Hydra. */}
        <form action="/login" method="get" className="grid gap-4">
          <Field label="Client" htmlFor="client">
            <select id="client" name="client" defaultValue={selected} className={SELECT}>
              {clients().map((c) => (
                <option key={c.id} value={c.id}>{`${c.label} · tenant ${c.tenant}${c.thirdParty ? " · third party, asks for consent" : ""} · ${c.id}`}</option>
              ))}
            </select>
          </Field>
          <Field label="acr_values" htmlFor="acr_values">
            <select id="acr_values" name="acr_values" className={SELECT}>
              <option value="">none</option>
              <option value="faye:1fa">faye:1fa</option>
              <option value="faye:2fa">faye:2fa (step up)</option>
            </select>
          </Field>
          <Field label="prompt" htmlFor="prompt">
            <select id="prompt" name="prompt" className={SELECT}>
              <option value="">default</option>
              <option value="login">login</option>
              <option value="consent">consent</option>
            </select>
          </Field>
          <div className="grid grid-cols-2 gap-4">
            <Field label="max_age" htmlFor="max_age">
              <input id="max_age" name="max_age" type="number" min={0} placeholder="seconds" className={SELECT} />
            </Field>
            <Field label="ui_locales" htmlFor="ui_locales">
              <input id="ui_locales" name="ui_locales" placeholder="en" className={SELECT} />
            </Field>
          </div>
          <Button type="submit" size="lg">Sign in</Button>
        </form>
      </CardContent>
    </Card>
  );
}

const SELECT = "h-9 w-full rounded-md border bg-background px-3 font-mono text-sm";

function Field({ label, htmlFor, children }: { label: string; htmlFor: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1">
      <label htmlFor={htmlFor} className="font-mono text-sm text-muted-foreground">{label}</label>
      {children}
    </div>
  );
}

/** What the preview run did, and whether the token's amr and acr are what the Paths tab predicted. */
function PreviewResult({ run, claims }: { run: Awaited<ReturnType<typeof previewRun>>; claims: Record<string, unknown> }) {
  if (!run) {
    return (
      <Alert>
        <AlertTitle>Preview run not found</AlertTitle>
        <AlertDescription>Its trace expires with the flow, 30 minutes after it started.</AlertDescription>
      </Alert>
    );
  }
  const match = matching(run, claims.amr, claims.acr);
  return (
    <Card>
      <CardHeader>
        <CardTitle>Preview run</CardTitle>
        <CardDescription>
          Tenant <span className="font-mono">{run.tenant}</span>, {run.draft ? "draft" : "published version"}{" "}
          <span className="font-mono">{run.versionHash.slice(7, 15)}</span>
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        <Alert variant={match ? "default" : "destructive"} data-expectation={match ? "match" : "mismatch"}>
          <AlertTitle>{match ? "Token matches the predicted path" : "Token does not match any predicted path"}</AlertTitle>
          <AlertDescription>
            <span className="font-mono">
              amr {JSON.stringify(claims.amr ?? [])} acr {JSON.stringify(claims.acr ?? null)}
            </span>
            {match ? (
              <span className="block font-mono text-xs break-all">{match.path}</span>
            ) : run.expected.length === 0 ? (
              <span className="block">The checker enumerated no path that makes the decisions and calls this run made.</span>
            ) : (
              run.expected.map((p) => (
                <span key={p.path} className="block font-mono text-xs break-all">
                  predicted {p.succeeds ? `${p.amr.join("+")} ${p.acr}` : "failure"}: {p.path}
                </span>
              ))
            )}
          </AlertDescription>
        </Alert>
        <ol className="grid gap-1 font-mono text-xs" id="trace">
          {run.events.map((e, i) => (
            <li key={i} className="grid grid-cols-[--spacing(16)_1fr] gap-2">
              <span className="text-muted-foreground">{e.event}</span>
              <span className="min-w-0 break-words">{describe(e)}</span>
            </li>
          ))}
        </ol>
      </CardContent>
    </Card>
  );
}
