import { Alert, AlertDescription, AlertTitle } from "@faye/ui/components/alert";
import { FayeLogo } from "@faye/ui/components/faye-logo";
import { ChevronRight, CircleX } from "lucide-react";
import Link from "next/link";
import { adminFetch } from "../../lib/faye";
import { toneOf } from "../../lib/kinds";
import type { TenantView } from "./shell";
import { Tile } from "./ui";

/** The console's front door: no sidebar, just the tenants to choose from. Everything else lives inside one. */
export default async function Home() {
  const res = await adminFetch("tenants");
  const tenants = res.ok ? ((await res.json()) as TenantView[]) : null;
  return (
    <main className="page-enter grid min-h-svh place-items-center bg-background px-4 py-12">
      <div className="grid w-full max-w-sm gap-6">
        <FayeLogo className="mx-auto h-8 w-auto" />
        <h1 className="text-center text-lg leading-7 font-semibold tracking-tight">Choose a tenant</h1>
        {tenants === null ? (
          <Alert variant="destructive" className="grid-cols-[auto_1fr] gap-x-2">
            <CircleX className="row-span-2 mt-0.5 size-4" />
            <AlertTitle className="text-sm">Admin API request failed ({res.status})</AlertTitle>
            <AlertDescription className="text-xs">Check that FAYE_ADMIN_TOKEN matches on the server and in the admin app.</AlertDescription>
          </Alert>
        ) : tenants.length === 0 ? (
          <p className="rounded-xl border border-dashed px-4 py-6 text-center text-sm text-muted-foreground">No tenants configured</p>
        ) : (
          <ul className="divide-y overflow-hidden rounded-xl border bg-card shadow-sm">
            {tenants.map((t) => (
              <li key={t.id}>
                <Link
                  href={`/tenants/${encodeURIComponent(t.id)}/flow`}
                  className="group flex items-center gap-3 px-4 py-3 outline-none hover:bg-accent focus-visible:bg-accent"
                >
                  <Tile letter={t.id} tone={toneOf(t.id)} size="lg" />
                  <span className="grid min-w-0 flex-1">
                    <span className="truncate font-mono font-medium">{t.id}</span>
                    <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                      {t.current ? (
                        <>
                          <span className="size-1.5 rounded-full bg-success" />
                          <span className="font-mono">{t.current.slice(7, 15)}</span>
                        </>
                      ) : (
                        "No live version"
                      )}
                    </span>
                  </span>
                  <ChevronRight className="size-4 text-muted-foreground transition-transform group-hover:translate-x-0.5" />
                </Link>
              </li>
            ))}
          </ul>
        )}
        <p className="text-center text-xs text-muted-foreground">
          <Link href="/docs" className="underline-offset-4 hover:text-foreground hover:underline">
            Docs
          </Link>
        </p>
      </div>
    </main>
  );
}
