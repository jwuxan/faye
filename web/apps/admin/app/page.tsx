import { Button } from "@faye/ui/components/button";
import { ArrowRight, CircleX, LogOut } from "lucide-react";
import Link from "next/link";
import { adminFetch } from "../lib/faye";
import { currentSession } from "../lib/session";
import { Wordmark } from "./wordmark";

type TenantView = { id: string; current: string | null };

export default async function Home({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  const session = await currentSession();

  if (!session) {
    return (
      <main className="grid min-h-svh place-items-center bg-surface-2 p-4">
        <div className="grid w-full max-w-[340px] gap-6">
          <Wordmark />
          <div className="grid gap-1">
            <h1 className="text-[20px] leading-7 font-semibold tracking-tight">Sign in to the console</h1>
            <p className="text-muted-foreground">Password and authenticator code required.</p>
          </div>
          {error && (
            <p className="flex gap-2 rounded-md bg-danger/8 px-3 py-2 text-xs" role="alert">
              <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
              {error}
            </p>
          )}
          <Button asChild className="h-9 w-full">
            <a href="/signin">
              Continue
              <ArrowRight className="size-4" />
            </a>
          </Button>
        </div>
      </main>
    );
  }

  const res = await adminFetch("tenants");
  const tenants = res.ok ? ((await res.json()) as TenantView[]) : null;
  return (
    <div className="grid min-h-svh grid-rows-[48px_1fr]">
      <header className="flex items-center gap-3 border-b px-4">
        <Wordmark />
        <span className="ml-auto font-mono text-xs text-muted-foreground">{session.sub}</span>
        <form action="/signout" method="post">
          <button type="submit" aria-label="Sign out" title="Sign out" className="grid size-7 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground">
            <LogOut className="size-4" strokeWidth={1.75} />
          </button>
        </form>
      </header>
      <main className="px-6 py-6">
        <div className="flex items-baseline gap-2 pb-4">
          <h1 className="text-[20px] leading-7 font-semibold tracking-tight">Tenants</h1>
          {tenants && <span className="font-mono text-xs text-muted-foreground">{tenants.length}</span>}
        </div>
        {tenants === null ? (
          <p className="flex gap-2 rounded-md bg-danger/8 px-3 py-2 text-xs" role="alert">
            <CircleX className="mt-0.5 size-3.5 shrink-0 text-danger" />
            The Faye server refused the admin API ({res.status}). Check FAYE_ADMIN_TOKEN.
          </p>
        ) : (
          <table className="w-full border-collapse text-[13px]">
            <thead className="text-left text-xs text-muted-foreground">
              <tr className="h-8 border-y bg-surface-2">
                <th className="w-72 pl-3 font-medium">Tenant</th>
                <th className="font-medium">Live version</th>
                <th className="w-10" />
              </tr>
            </thead>
            <tbody>
              {tenants.map((t) => (
                <tr key={t.id} className="group relative h-10 border-b hover:bg-accent">
                  <td className="pl-3">
                    <Link href={`/tenants/${encodeURIComponent(t.id)}`} className="font-mono font-medium after:absolute after:inset-0">
                      {t.id}
                    </Link>
                  </td>
                  <td>
                    {t.current ? (
                      <span className="flex items-center gap-1.5 font-mono text-xs text-muted-foreground">
                        <span className="size-1.5 rounded-full bg-success" />
                        {t.current.slice(7, 15)}
                      </span>
                    ) : (
                      <span className="text-xs text-muted-foreground">none</span>
                    )}
                  </td>
                  <td className="pr-3 text-right">
                    <ArrowRight className="ml-auto size-4 text-muted-foreground opacity-0 group-hover:opacity-100" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </main>
    </div>
  );
}
