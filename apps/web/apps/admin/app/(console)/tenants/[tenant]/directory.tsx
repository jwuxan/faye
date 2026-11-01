"use client";

import { Alert, AlertDescription, AlertTitle } from "@faye/ui/components/alert";
import { Avatar, AvatarFallback } from "@faye/ui/components/avatar";
import { Badge } from "@faye/ui/components/badge";
import { Button } from "@faye/ui/components/button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@faye/ui/components/input-group";
import { Skeleton } from "@faye/ui/components/skeleton";
import { cn } from "@faye/ui/lib/utils";
import { TILE, toneOf } from "../../../../lib/kinds";
import { AppWindow, ChevronLeft, ChevronRight, CircleX, Fingerprint, KeyRound, Lock, LogIn, type LucideIcon, Search, TimerReset, Users } from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import { api, type Client, type UserView } from "../../../../lib/api";
import { PageHeader } from "../../shell";
import { CopyButton, Empty, Help, IconButton, PaneBar, Tile } from "../../ui";
import { useDraft } from "./draft";

/** Loads a read only list; null while loading, an error message if the server could not list it. */
function useList<T>(path: string): { rows: T[] | null; error: string | null } {
  const { fail } = useDraft();
  const [rows, setRows] = useState<T[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    api<T[]>(path)
      .then(({ body }) => live && setRows(body))
      .catch((e: Error) => {
        if (!live) return;
        setError(e.message);
        fail(e);
      });
    return () => {
      live = false;
    };
  }, [path, fail]);
  return { rows, error };
}

function DirectoryFrame({ title, description, readOnly, children }: { title: string; description: string; readOnly: ReactNode; children: ReactNode }) {
  const { tenant } = useDraft();
  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader tenant={tenant} title={title}>
        <Badge variant="outline" className="gap-1 font-normal text-muted-foreground">
          <Lock className="size-3" />
          Read only
        </Badge>
        <Help>
          {description} Managed outside Faye. {readOnly}
        </Help>
      </PageHeader>
      <div className="grid min-h-0 flex-1 grid-rows-[auto_minmax(0,1fr)] md:grid-cols-[--spacing(80)_minmax(0,1fr)] md:grid-rows-1">{children}</div>
    </div>
  );
}

/** The list half of a directory page: the search bar, the rows, an optional footer. */
function ListPane({ query, onQuery, placeholder, children }: { query: string; onQuery: (q: string) => void; placeholder: string; children: ReactNode }) {
  return (
    <div className="flex max-h-[38svh] min-h-0 flex-col border-b bg-surface-2 md:max-h-none md:border-r md:border-b-0">
      <PaneBar>
        <InputGroup className="h-7 w-full shadow-none">
          <InputGroupAddon>
            <Search />
          </InputGroupAddon>
          <InputGroupInput placeholder={placeholder} value={query} onChange={(e) => onQuery(e.target.value)} aria-label={placeholder} />
        </InputGroup>
      </PaneBar>
      {children}
    </div>
  );
}

/** One row of a directory list: two lines, the second indented under the first's icon. */
function ListItem({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-current={selected}
      onClick={onClick}
      className={cn(
        "grid w-full gap-0.5 rounded-lg px-2 py-1.5 text-left transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring/50",
        selected ? "bg-sidebar-accent text-sidebar-accent-foreground" : "hover:bg-accent",
      )}
    >
      {children}
    </button>
  );
}

function Loading() {
  return (
    <div className="grid content-start gap-0.5 p-2">
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-9 w-full" />
      ))}
    </div>
  );
}

function Failed({ what, message }: { what: string; message: string }) {
  return (
    <Alert variant="destructive" className="m-4 flex gap-3">
      <CircleX className="mt-0.5 size-4 shrink-0" />
      <div className="grid gap-0.5">
        <AlertTitle className="text-sm">Could not load {what}</AlertTitle>
        <AlertDescription className="text-xs">{message}</AlertDescription>
      </div>
    </Alert>
  );
}

const matches = (q: string, ...fields: (string | null)[]) => fields.some((f) => f?.toLowerCase().includes(q.trim().toLowerCase()));

/** Public clients authenticate with PKCE only; confidential ones hold a secret. */
const kindOf = (c: Client) => (c.authMethod === "none" ? "Public" : c.grantTypes.includes("client_credentials") && c.redirectUris.length === 0 ? "Machine" : "Confidential");

/**
 * Starts a sign in as the application, through the demo client (the relying party of every client
 * bootstrap registers), in a new tab. prompt=login, so each try runs the tenant's flow from the start.
 */
function TrySignIn({ client, demoClient, label = "Try sign in" }: { client: Client; demoClient: string; label?: string }) {
  const href = `${demoClient}/login?${new URLSearchParams({ client: client.id, prompt: "login" })}`;
  return (
    <Button asChild variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={(e) => e.stopPropagation()}>
      <a href={href} target="_blank" rel="noreferrer">
        <LogIn className="size-3.5" />
        {label}
      </a>
    </Button>
  );
}

/** The tenant's OAuth clients, read from the authorization server: they all sign people in with this tenant's flow and users. */
export function ApplicationsPage() {
  const { tenant, demoClient } = useDraft();
  const { rows, error } = useList<Client>(`tenants/${tenant}/clients`);
  const [query, setQuery] = useState("");
  const [chosen, setChosen] = useState<string | null>(null);
  const shown = rows?.filter((c) => matches(query, c.id, c.name)) ?? null;
  const selected = (chosen ? shown?.find((c) => c.id === chosen) : null) ?? shown?.[0] ?? null;
  return (
    <DirectoryFrame
      title="Applications"
      description={`OAuth clients that sign in with the ${tenant} login flow and users.`}
      readOnly={
        <>
          Registered in the authorization server, which issues client IDs and secrets. Faye lists clients with{" "}
          <code className="font-mono">metadata.tenant = {tenant}</code>; it never creates, changes or deletes them, and never sees a secret after issue.
        </>
      }
    >
      {rows !== null && rows.length === 0 && !error ? (
        <div className="row-span-2 min-h-0 md:col-span-2">
          <Empty icon={AppWindow} title="No applications">
            <p className="max-w-sm">
              Register a client in the authorization server with <code className="font-mono">metadata.tenant</code> set to{" "}
              <code className="font-mono">{tenant}</code>. Without Hydra, the in-memory host has no clients.
            </p>
          </Empty>
        </div>
      ) : (
        <>
          <ListPane query={query} onQuery={setQuery} placeholder="Search by name or client ID">
            {!shown ? (
              <Loading />
            ) : (
              <ul className="grid content-start gap-0.5 overflow-auto p-2" aria-label="Applications">
                {shown.map((c) => (
                  <li key={c.id}>
                    <ListItem selected={selected?.id === c.id} onClick={() => setChosen(c.id)}>
                      <span className="flex items-center gap-2">
                        <Tile icon={AppWindow} tone={toneOf(c.id)} size="sm" />
                        <span className="truncate text-sm font-medium">{c.name || c.id}</span>
                        {c.preview && (
                          <Badge variant="outline" className="border-accent-ui/40 font-normal text-accent-ui">
                            Preview
                          </Badge>
                        )}
                      </span>
                      <span className="truncate pl-7 text-xs text-muted-foreground">
                        {kindOf(c)} · {c.firstParty ? "First party" : "Third party"}
                      </span>
                    </ListItem>
                  </li>
                ))}
                {shown.length === 0 && <li className="px-2 py-1.5 text-xs text-muted-foreground">No matching applications</li>}
              </ul>
            )}
          </ListPane>
          <div className="flex min-h-0 min-w-0 flex-col">
            {error ? (
              <Failed what="applications" message={error} />
            ) : (
              selected && (
                <>
                  <PaneBar>
                    <span className="truncate text-sm font-medium">{selected.name || selected.id}</span>
                    <span className="min-w-0 truncate font-mono text-xs text-muted-foreground">{selected.id}</span>
                    <span className="ml-auto flex shrink-0 items-center gap-1">
                      <Help>Opens the demo client in a new tab and runs the {selected.preview ? "draft" : "live version"}.</Help>
                      <TrySignIn client={selected} demoClient={demoClient} />
                    </span>
                  </PaneBar>
                  <div className="min-h-0 flex-1 overflow-x-hidden overflow-y-auto">
                    <section className="grid gap-3 border-b px-4 pt-4 pb-5">
                      <dl className="grid max-w-2xl gap-4">
                        <Detail label="Client ID">
                          <Copyable value={selected.id} />
                        </Detail>
                        <Detail label="Client secret">
                          {selected.authMethod === "none" ? (
                            <span className="text-muted-foreground">None. Public client; uses PKCE.</span>
                          ) : (
                            <span className="flex items-start gap-2 text-muted-foreground">
                              <Lock className="mt-0.5 size-3.5 shrink-0" />
                              Stored by the authorization server; shown only once, at registration.
                            </span>
                          )}
                        </Detail>
                        <Detail label="Token endpoint authentication">
                          <code className="font-mono text-xs">{selected.authMethod}</code>
                        </Detail>
                      </dl>
                    </section>
                    <section className="border-b px-4 pt-4 pb-5">
                      <dl className="grid max-w-2xl gap-4">
                        <Detail label="Callback URLs">
                          {selected.redirectUris.length === 0 ? (
                            <span className="text-muted-foreground">None</span>
                          ) : (
                            <ul className="grid gap-1">
                              {selected.redirectUris.map((u) => (
                                <li key={u} className="font-mono text-xs break-all">
                                  {u}
                                </li>
                              ))}
                            </ul>
                          )}
                        </Detail>
                        <Detail label="Grant types">
                          <span className="flex flex-wrap gap-1">
                            {selected.grantTypes.map((g) => (
                              <Badge key={g} variant="secondary" className="font-mono text-xs font-normal">
                                {g}
                              </Badge>
                            ))}
                          </span>
                        </Detail>
                      </dl>
                    </section>
                    <section className="border-b px-4 pt-4 pb-5">
                      <dl className="grid max-w-2xl gap-4">
                        <Detail label="Tenant">
                          <code className="font-mono text-xs">{selected.tenant}</code>
                        </Detail>
                        {selected.preview && (
                          <Detail label="Preview">
                            <span className="text-muted-foreground">Runs the draft instead of the live version when previews are enabled.</span>
                          </Detail>
                        )}
                        {selected.createdAt && (
                          <Detail label="Registered">
                            <span className="font-mono text-xs">{new Date(selected.createdAt).toLocaleString()}</span>
                          </Detail>
                        )}
                      </dl>
                    </section>
                  </div>
                </>
              )
            )}
          </div>
        </>
      )}
    </DirectoryFrame>
  );
}

/** The accounts flows sign in, read from the user directory. */
export function UsersPage() {
  const { tenant } = useDraft();
  const { rows, error } = useList<UserView>(`tenants/${tenant}/users`);
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [chosen, setChosen] = useState<string | null>(null);
  const found = rows?.filter((u) => matches(query, u.identifier, u.name, u.subject)) ?? null;
  const pages = found ? Math.max(1, Math.ceil(found.length / PAGE)) : 1;
  const at = Math.min(page, pages - 1);
  const shown = found?.slice(at * PAGE, (at + 1) * PAGE) ?? null;
  const selected = (chosen ? found?.find((u) => u.subject === chosen) : null) ?? shown?.[0] ?? null;
  return (
    <DirectoryFrame
      title="Users"
      description={`Accounts the ${tenant} applications can sign in, and their enrolled credentials.`}
      readOnly={
        <>
          Faye only reads the user directory. A tenant’s accounts are unknown to other tenants’ applications; shared accounts work for all. Credentials are
          enrolled only through a flow. Passwords and secrets are never shown, only whether they exist.
        </>
      }
    >
      {rows !== null && rows.length === 0 && !error ? (
        <div className="row-span-2 min-h-0 md:col-span-2">
          <Empty icon={Users} title="No users" />
        </div>
      ) : (
        <>
          <ListPane
            query={query}
            onQuery={(q) => {
              setQuery(q);
              setPage(0);
            }}
            placeholder="Search by email, name or subject"
          >
            {!shown ? (
              <Loading />
            ) : (
              <>
                <ul className="grid content-start gap-0.5 overflow-auto p-2" aria-label="Users">
                  {shown.map((u) => (
                    <li key={u.subject}>
                      <ListItem selected={selected?.subject === u.subject} onClick={() => setChosen(u.subject)}>
                        <span className="flex items-center gap-2">
                          <Avatar className="size-5">
                            <AvatarFallback className={cn("text-xs font-medium text-on-fill", TILE[toneOf(u.subject)])}>{initials(u)}</AvatarFallback>
                          </Avatar>
                          <span className="truncate text-sm font-medium">{u.name ?? u.identifier}</span>
                        </span>
                        <span className="truncate pl-7 text-xs text-muted-foreground">
                          {u.identifier}
                          {!u.emailVerified && " · not verified"}
                        </span>
                      </ListItem>
                    </li>
                  ))}
                  {shown.length === 0 && <li className="px-2 py-1.5 text-xs text-muted-foreground">No matching users</li>}
                </ul>
                <div className="flex h-10 shrink-0 items-center gap-1 border-t px-4 text-xs text-muted-foreground">
                  {found!.length === 0 ? (
                    "No matching users"
                  ) : (
                    <span>
                      {at * PAGE + 1}–{Math.min((at + 1) * PAGE, found!.length)} of {found!.length}
                    </span>
                  )}
                  <IconButton icon={ChevronLeft} label="Previous page" className="ml-auto" disabled={at === 0} onClick={() => setPage(at - 1)} />
                  <IconButton icon={ChevronRight} label="Next page" className="-mr-1.5" disabled={at >= pages - 1} onClick={() => setPage(at + 1)} />
                </div>
              </>
            )}
          </ListPane>
          <div className="flex min-h-0 min-w-0 flex-col">
            {error ? (
              <Failed what="users" message={error} />
            ) : (
              selected && (
                <>
                  <PaneBar>
                    <span className="truncate text-sm font-medium">{selected.name ?? selected.identifier}</span>
                  </PaneBar>
                  <div className="min-h-0 flex-1 overflow-x-hidden overflow-y-auto">
                    <section className="border-b px-4 pt-4 pb-5">
                      <div className="grid max-w-2xl gap-4">
                        <div className="flex items-center gap-3">
                          <Avatar className="size-8">
                            <AvatarFallback className={cn("text-sm font-medium text-on-fill", TILE[toneOf(selected.subject)])}>{initials(selected)}</AvatarFallback>
                          </Avatar>
                          <span className="grid gap-0.5">
                            <span className="text-sm font-medium">{selected.name ?? selected.identifier}</span>
                            <span className="text-xs text-muted-foreground">{selected.identifier}</span>
                          </span>
                        </div>
                        <dl className="grid gap-4">
                          <Detail label="Subject">
                            <Copyable value={selected.subject} />
                          </Detail>
                          <Detail label="Pool">
                            <Pool tenant={selected.tenant} />
                            <span className="text-xs text-muted-foreground">
                              {selected.tenant ? " · visible to this tenant’s applications only" : " · visible to every tenant’s applications"}
                            </span>
                          </Detail>
                          <Detail label="Email">
                            <span>{selected.identifier}</span>
                            <span className="text-xs text-muted-foreground"> · {selected.emailVerified ? "verified" : "not verified"}</span>
                          </Detail>
                        </dl>
                      </div>
                    </section>
                    <section className="border-b px-4 pt-4 pb-5">
                      <dl className="grid max-w-2xl gap-4">
                        <Detail label="Credentials">
                          <ul className="grid gap-2">
                            <CredentialRow icon={KeyRound} label="Password" on={selected.password} />
                            <CredentialRow icon={TimerReset} label="Authenticator app (TOTP)" on={selected.totp} />
                            <CredentialRow
                              icon={Fingerprint}
                              label={selected.passkeys === 1 ? "1 passkey" : `${selected.passkeys || "No"} passkeys`}
                              on={selected.passkeys > 0}
                            />
                          </ul>
                        </Detail>
                        <Detail label="MFA">
                          <span className="text-muted-foreground">{selected.mfaRequired ? "Required for this account, regardless of tenant policy" : "Tenant policy"}</span>
                        </Detail>
                      </dl>
                    </section>
                  </div>
                </>
              )
            )}
          </div>
        </>
      )}
    </DirectoryFrame>
  );
}

/** Rows per page of the users list. */
const PAGE = 50;

function Pool({ tenant }: { tenant: string | null }) {
  return tenant ? (
    <Badge variant="outline" className="font-mono font-normal">
      {tenant}
    </Badge>
  ) : (
    <Badge variant="secondary" className="font-normal text-muted-foreground">
      Shared
    </Badge>
  );
}

const initials = (u: UserView) =>
  (u.name ?? u.identifier)
    .split(/[\s@.]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]!.toUpperCase())
    .join("");

function CredentialRow({ icon: Icon, label, on }: { icon: LucideIcon; label: string; on: boolean }) {
  return (
    <li className={cn("flex items-center gap-2", !on && "text-muted-foreground")}>
      <Icon className="size-4" strokeWidth={1.75} />
      {label}
      <Badge variant={on ? "secondary" : "outline"} className="ml-auto font-normal">
        {on ? "Enrolled" : "Not enrolled"}
      </Badge>
    </li>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-1.5">
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  );
}

function Copyable({ value }: { value: string }) {
  return (
    <span className="flex items-center gap-1 rounded-md border bg-surface-2 py-0.5 pr-0.5 pl-2.5">
      <code className="min-w-0 flex-1 truncate font-mono text-xs">{value}</code>
      <CopyButton value={value} />
    </span>
  );
}
