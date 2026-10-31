"use client";

import { Alert, AlertDescription, AlertTitle } from "@faye/ui/components/alert";
import { Avatar, AvatarFallback } from "@faye/ui/components/avatar";
import { Badge } from "@faye/ui/components/badge";
import { Button } from "@faye/ui/components/button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@faye/ui/components/input-group";
import { Separator } from "@faye/ui/components/separator";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@faye/ui/components/sheet";
import { Skeleton } from "@faye/ui/components/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@faye/ui/components/table";
import { cn } from "@faye/ui/lib/utils";
import { AppWindow, ChevronLeft, ChevronRight, CircleX, Copy, Fingerprint, KeyRound, Lock, LogIn, type LucideIcon, Search, TimerReset, Users } from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import { api, type Client, type UserView } from "../../../lib/api";
import { PageHeader } from "../../shell";
import { Empty, IconButton } from "../../ui";
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

function DirectoryFrame({
  title,
  description,
  readOnly,
  count,
  query,
  onQuery,
  placeholder,
  children,
}: {
  title: string;
  description: string;
  readOnly: ReactNode;
  count: number | null;
  query: string;
  onQuery: (q: string) => void;
  placeholder: string;
  children: ReactNode;
}) {
  const { tenant } = useDraft();
  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader tenant={tenant} title={title}>
        <Badge variant="outline" className="gap-1 font-normal text-muted-foreground">
          <Lock className="size-3" />
          Read only
        </Badge>
      </PageHeader>
      <div className="min-h-0 flex-1 overflow-auto">
        <div className="mx-auto grid max-w-6xl gap-6 px-6 py-8">
          <div className="grid gap-1">
            <div className="flex items-baseline gap-2">
              <h1 className="text-[20px] leading-7 font-semibold tracking-tight">{title}</h1>
              {count !== null && <span className="font-mono text-xs text-muted-foreground">{count}</span>}
            </div>
            <p className="text-muted-foreground">{description}</p>
          </div>
          <Alert className="flex gap-3 bg-surface-2">
            <Lock className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <div className="grid gap-0.5">
              <AlertTitle className="text-[13px]">Managed outside Faye</AlertTitle>
              <AlertDescription className="text-xs">{readOnly}</AlertDescription>
            </div>
          </Alert>
          <InputGroup className="max-w-sm">
            <InputGroupAddon>
              <Search />
            </InputGroupAddon>
            <InputGroupInput placeholder={placeholder} value={query} onChange={(e) => onQuery(e.target.value)} aria-label={placeholder} />
          </InputGroup>
          {children}
        </div>
      </div>
    </div>
  );
}

function Loading() {
  return (
    <div className="grid gap-2">
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-11 w-full" />
      ))}
    </div>
  );
}

function Failed({ message }: { message: string }) {
  return (
    <Alert variant="destructive" className="flex gap-3">
      <CircleX className="mt-0.5 size-4 shrink-0" />
      <div className="grid gap-0.5">
        <AlertTitle className="text-[13px]">Could not list them</AlertTitle>
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
  const [open, setOpen] = useState<Client | null>(null);
  const shown = rows?.filter((c) => matches(query, c.id, c.name)) ?? null;
  return (
    <DirectoryFrame
      title="Applications"
      description={`OAuth clients whose sign ins run the ${tenant} tenant’s flow. They share its users.`}
      readOnly={
        <>
          Clients are registered in the authorization server, which generates their ids and secrets. Faye reads them to show which run this tenant’s flow
          (<code className="font-mono">metadata.tenant = {tenant}</code>); it never creates, changes or deletes one, and never sees a secret after it is issued.
        </>
      }
      count={rows?.length ?? null}
      query={query}
      onQuery={setQuery}
      placeholder="Search by name or client id"
    >
      {error ? (
        <Failed message={error} />
      ) : !shown ? (
        <Loading />
      ) : rows!.length === 0 ? (
        <div className="rounded-lg border">
          <Empty icon={AppWindow} title="No applications for this tenant">
            <p className="max-w-sm">
              Register a client in the authorization server with <code className="font-mono">metadata.tenant</code> set to{" "}
              <code className="font-mono">{tenant}</code>. Without Hydra, the in-memory host has no clients.
            </p>
          </Empty>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border">
          <Table className="text-[13px]">
            <TableHeader className="bg-surface-2">
              <TableRow>
                <TableHead className="pl-4 text-xs">Application</TableHead>
                <TableHead className="text-xs">Type</TableHead>
                <TableHead className="text-xs">Party</TableHead>
                <TableHead className="text-xs">Grants</TableHead>
                <TableHead className="text-xs">Callback URLs</TableHead>
                <TableHead className="w-32 pr-4" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map((c) => (
                <TableRow key={c.id} className="h-14 cursor-pointer" onClick={() => setOpen(c)}>
                  <TableCell className="pl-4">
                    <button type="button" className="flex items-center gap-3 text-left outline-none focus-visible:underline" onClick={() => setOpen(c)}>
                      <span className="grid size-8 shrink-0 place-items-center rounded-md border bg-surface-2 text-muted-foreground">
                        <AppWindow className="size-4" strokeWidth={1.75} />
                      </span>
                      <span className="grid">
                        <span className="font-medium">{c.name || c.id}</span>
                        <span className="font-mono text-xs text-muted-foreground">{c.id}</span>
                      </span>
                    </button>
                  </TableCell>
                  <TableCell>
                    <span className="flex items-center gap-1.5">
                      {kindOf(c)}
                      {c.preview && (
                        <Badge variant="outline" className="border-accent-ui/40 font-normal text-accent-ui">
                          preview
                        </Badge>
                      )}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{c.firstParty ? "First party" : "Third party"}</TableCell>
                  <TableCell>
                    <span className="flex flex-wrap gap-1">
                      {c.grantTypes.map((g) => (
                        <Badge key={g} variant="secondary" className="font-mono text-[11px] font-normal">
                          {g}
                        </Badge>
                      ))}
                    </span>
                  </TableCell>
                  <TableCell className="max-w-64 truncate font-mono text-xs text-muted-foreground">{c.redirectUris.join(", ") || "none"}</TableCell>
                  <TableCell className="pr-4 text-right">
                    <TrySignIn client={c} demoClient={demoClient} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <Sheet open={open !== null} onOpenChange={(o) => !o && setOpen(null)}>
        <SheetContent className="w-full gap-0 sm:max-w-md">
          {open && (
            <>
              <SheetHeader className="border-b">
                <SheetTitle>{open.name || open.id}</SheetTitle>
                <SheetDescription>
                  {kindOf(open)} · {open.firstParty ? "first party" : "third party, asks for consent"}
                </SheetDescription>
              </SheetHeader>
              <dl className="grid gap-5 overflow-auto p-4">
                <Detail label="Try it">
                  <span className="grid justify-items-start gap-1.5">
                    <TrySignIn client={open} demoClient={demoClient} label="Sign in as this application" />
                    <span className="text-xs text-muted-foreground">Opens the demo client in a new tab and runs the {open.tenant} tenant’s live flow{open.preview ? " (its draft, for this preview client)" : ""}.</span>
                  </span>
                </Detail>
                <Detail label="Client ID">
                  <Copyable value={open.id} />
                </Detail>
                <Detail label="Client secret">
                  {open.authMethod === "none" ? (
                    <span className="text-muted-foreground">None: a public client, which proves itself with PKCE.</span>
                  ) : (
                    <span className="flex items-start gap-2 text-muted-foreground">
                      <Lock className="mt-0.5 size-3.5 shrink-0" />
                      Held by the authorization server, which showed it once when the client was registered.
                    </span>
                  )}
                </Detail>
                <Detail label="Token endpoint authentication">
                  <code className="font-mono text-xs">{open.authMethod}</code>
                </Detail>
                <Separator />
                <Detail label="Callback URLs">
                  {open.redirectUris.length === 0 ? (
                    <span className="text-muted-foreground">none</span>
                  ) : (
                    <ul className="grid gap-1">
                      {open.redirectUris.map((u) => (
                        <li key={u} className="font-mono text-xs break-all">
                          {u}
                        </li>
                      ))}
                    </ul>
                  )}
                </Detail>
                <Detail label="Grant types">
                  <span className="flex flex-wrap gap-1">
                    {open.grantTypes.map((g) => (
                      <Badge key={g} variant="secondary" className="font-mono text-[11px] font-normal">
                        {g}
                      </Badge>
                    ))}
                  </span>
                </Detail>
                <Separator />
                <Detail label="Tenant">
                  <code className="font-mono text-xs">{open.tenant}</code>
                  <span className="text-xs text-muted-foreground"> · its flow, users and branding</span>
                </Detail>
                {open.preview && (
                  <Detail label="Preview">
                    <span className="text-muted-foreground">Runs the tenant’s draft instead of the live version, when previews are on.</span>
                  </Detail>
                )}
                {open.createdAt && (
                  <Detail label="Registered">
                    <span className="font-mono text-xs">{new Date(open.createdAt).toLocaleString()}</span>
                  </Detail>
                )}
              </dl>
            </>
          )}
        </SheetContent>
      </Sheet>
    </DirectoryFrame>
  );
}

/** The accounts flows sign in, read from the user directory. */
export function UsersPage() {
  const { tenant } = useDraft();
  const { rows, error } = useList<UserView>(`tenants/${tenant}/users`);
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<UserView | null>(null);
  const found = rows?.filter((u) => matches(query, u.identifier, u.name, u.subject)) ?? null;
  const pages = found ? Math.max(1, Math.ceil(found.length / PAGE)) : 1;
  const at = Math.min(page, pages - 1);
  const shown = found?.slice(at * PAGE, (at + 1) * PAGE) ?? null;
  return (
    <DirectoryFrame
      title="Users"
      description={`Accounts the ${tenant} tenant’s flows can sign in, and which credentials each has enrolled. Its applications all share them.`}
      readOnly={
        <>
          Accounts live in an external user directory that Faye only reads. An account of one tenant is unknown to every other tenant’s applications; shared
          accounts work for all. People are added and changed in that directory, and enroll credentials only through a flow. Passwords and secrets are never
          shown, only whether they exist.
        </>
      }
      count={rows?.length ?? null}
      query={query}
      onQuery={(q) => {
        setQuery(q);
        setPage(0);
      }}
      placeholder="Search by email, name or subject"
    >
      {error ? (
        <Failed message={error} />
      ) : !shown ? (
        <Loading />
      ) : rows!.length === 0 ? (
        <div className="rounded-lg border">
          <Empty icon={Users} title="No users">
            The directory is empty.
          </Empty>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border">
          <Table className="text-[13px]">
            <TableHeader className="bg-surface-2">
              <TableRow>
                <TableHead className="pl-4 text-xs">User</TableHead>
                <TableHead className="text-xs">Subject</TableHead>
                <TableHead className="text-xs">Pool</TableHead>
                <TableHead className="text-xs">Credentials</TableHead>
                <TableHead className="pr-4 text-xs">Policy</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map((u) => (
                <TableRow key={u.subject} className="h-14 cursor-pointer" onClick={() => setOpen(u)}>
                  <TableCell className="pl-4">
                    <button type="button" className="flex items-center gap-3 text-left outline-none focus-visible:underline" onClick={() => setOpen(u)}>
                      <Avatar className="size-8">
                        <AvatarFallback className="text-xs">{initials(u)}</AvatarFallback>
                      </Avatar>
                      <span className="grid">
                        <span className="font-medium">{u.name ?? u.identifier}</span>
                        <span className="text-xs text-muted-foreground">
                          {u.identifier}
                          {!u.emailVerified && " · not verified"}
                        </span>
                      </span>
                    </button>
                  </TableCell>
                  <TableCell className="max-w-48 truncate font-mono text-xs text-muted-foreground">{u.subject}</TableCell>
                  <TableCell>
                    <Pool tenant={u.tenant} />
                  </TableCell>
                  <TableCell>
                    <Credentials user={u} />
                  </TableCell>
                  <TableCell className="pr-4 text-xs text-muted-foreground">{u.mfaRequired ? "MFA required" : "Tenant policy"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <div className="flex h-11 items-center gap-2 border-t bg-surface-2 px-4 text-xs text-muted-foreground">
            {found!.length === 0 ? (
              "No account matches"
            ) : (
              <span>
                {at * PAGE + 1}–{Math.min((at + 1) * PAGE, found!.length)} of {found!.length}
              </span>
            )}
            <Button variant="outline" size="sm" className="ml-auto h-7" disabled={at === 0} onClick={() => setPage(at - 1)}>
              <ChevronLeft />
              Previous
            </Button>
            <Button variant="outline" size="sm" className="h-7" disabled={at >= pages - 1} onClick={() => setPage(at + 1)}>
              Next
              <ChevronRight />
            </Button>
          </div>
        </div>
      )}
      <Sheet open={open !== null} onOpenChange={(o) => !o && setOpen(null)}>
        <SheetContent className="w-full gap-0 sm:max-w-md">
          {open && (
            <>
              <SheetHeader className="flex-row items-center gap-3 border-b">
                <Avatar className="size-10">
                  <AvatarFallback>{initials(open)}</AvatarFallback>
                </Avatar>
                <div className="grid gap-0.5">
                  <SheetTitle>{open.name ?? open.identifier}</SheetTitle>
                  <SheetDescription>{open.identifier}</SheetDescription>
                </div>
              </SheetHeader>
              <dl className="grid gap-5 overflow-auto p-4">
                <Detail label="Subject">
                  <Copyable value={open.subject} />
                </Detail>
                <Detail label="Pool">
                  <Pool tenant={open.tenant} />
                  <span className="text-xs text-muted-foreground">
                    {open.tenant ? " · only this tenant’s applications find this account" : " · every tenant’s applications find this account"}
                  </span>
                </Detail>
                <Detail label="Email">
                  <span>{open.identifier}</span>
                  <span className="text-xs text-muted-foreground"> · {open.emailVerified ? "verified" : "not verified"}</span>
                </Detail>
                <Separator />
                <Detail label="Credentials">
                  <ul className="grid gap-2">
                    <CredentialRow icon={KeyRound} label="Password" on={open.password} />
                    <CredentialRow icon={TimerReset} label="Authenticator app (TOTP)" on={open.totp} />
                    <CredentialRow
                      icon={Fingerprint}
                      label={open.passkeys === 1 ? "1 passkey" : `${open.passkeys || "No"} passkeys`}
                      on={open.passkeys > 0}
                    />
                  </ul>
                </Detail>
                <Detail label="MFA">
                  <span className="text-muted-foreground">
                    {open.mfaRequired ? "Required for this account, whatever the tenant’s policy." : "As the tenant’s policy says."}
                  </span>
                </Detail>
              </dl>
            </>
          )}
        </SheetContent>
      </Sheet>
    </DirectoryFrame>
  );
}

/** Rows per page of the users table. */
const PAGE = 50;

function Pool({ tenant }: { tenant: string | null }) {
  return tenant ? (
    <Badge variant="outline" className="font-mono font-normal">
      {tenant}
    </Badge>
  ) : (
    <Badge variant="secondary" className="font-normal text-muted-foreground">
      shared
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

function Credentials({ user }: { user: UserView }) {
  const items = [user.password && "password", user.totp && "totp", user.passkeys > 0 && `passkey ×${user.passkeys}`].filter(Boolean) as string[];
  if (items.length === 0) return <span className="text-xs text-muted-foreground">none</span>;
  return (
    <span className="flex flex-wrap gap-1">
      {items.map((c) => (
        <Badge key={c} variant="secondary" className="font-mono text-[11px] font-normal">
          {c}
        </Badge>
      ))}
    </span>
  );
}

function CredentialRow({ icon: Icon, label, on }: { icon: LucideIcon; label: string; on: boolean }) {
  return (
    <li className={cn("flex items-center gap-2", !on && "text-muted-foreground")}>
      <Icon className="size-4" strokeWidth={1.75} />
      {label}
      <Badge variant={on ? "secondary" : "outline"} className="ml-auto font-normal">
        {on ? "enrolled" : "not enrolled"}
      </Badge>
    </li>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-1.5">
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className="text-[13px]">{children}</dd>
    </div>
  );
}

function Copyable({ value }: { value: string }) {
  return (
    <span className="flex items-center gap-1 rounded-md border bg-surface-2 py-0.5 pr-0.5 pl-2.5">
      <code className="min-w-0 flex-1 truncate font-mono text-xs">{value}</code>
      <IconButton icon={Copy} label="Copy" onClick={() => navigator.clipboard?.writeText(value)} />
    </span>
  );
}
