import { Alert, AlertDescription, AlertTitle } from "@faye/ui/components/alert";
import { Badge } from "@faye/ui/components/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@faye/ui/components/table";
import { ArrowRight, CircleX } from "lucide-react";
import Link from "next/link";
import { adminFetch } from "../lib/faye";
import { sidebarOpen } from "../lib/sidebar";
import { PageHeader, Shell, type TenantView } from "./shell";

export default async function Home() {
  const res = await adminFetch("tenants");
  const tenants = res.ok ? ((await res.json()) as TenantView[]) : null;
  return (
    <Shell tenant={null} tenants={tenants ?? []} defaultOpen={await sidebarOpen()}>
      <PageHeader title="Tenants" />
      <div className="min-h-0 flex-1 overflow-auto">
        <div className="mx-auto grid max-w-5xl gap-6 px-6 py-8">
          <div className="grid gap-1">
            <div className="flex items-baseline gap-2">
              <h1 className="text-[20px] leading-7 font-semibold tracking-tight">Tenants</h1>
              {tenants && <span className="font-mono text-xs text-muted-foreground">{tenants.length}</span>}
            </div>
            <p className="text-muted-foreground">Each tenant has its own login flow, hooks, versions and settings. Its OAuth clients run that flow.</p>
          </div>
          {tenants === null ? (
            <Alert variant="destructive" className="grid-cols-[auto_1fr] gap-x-2">
              <CircleX className="row-span-2 mt-0.5 size-4" />
              <AlertTitle className="text-[13px]">The Faye server refused the admin API ({res.status})</AlertTitle>
              <AlertDescription className="text-xs">Check FAYE_ADMIN_TOKEN on the server and in this app.</AlertDescription>
            </Alert>
          ) : (
            <div className="overflow-hidden rounded-lg border">
              <Table className="text-[13px]">
                <TableHeader className="bg-surface-2">
                  <TableRow>
                    <TableHead className="w-72 pl-4 text-xs">Tenant</TableHead>
                    <TableHead className="text-xs">Live version</TableHead>
                    <TableHead className="w-10" />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {tenants.map((t) => (
                    <TableRow key={t.id} className="group relative h-11">
                      <TableCell className="pl-4">
                        <Link href={`/tenants/${encodeURIComponent(t.id)}/flow`} className="font-mono font-medium after:absolute after:inset-0">
                          {t.id}
                        </Link>
                      </TableCell>
                      <TableCell>
                        {t.current ? (
                          <Badge variant="outline" className="gap-1.5 font-mono font-normal text-muted-foreground">
                            <span className="size-1.5 rounded-full bg-success" />
                            {t.current.slice(7, 15)}
                          </Badge>
                        ) : (
                          <span className="text-xs text-muted-foreground">none</span>
                        )}
                      </TableCell>
                      <TableCell className="pr-4 text-right">
                        <ArrowRight className="ml-auto size-4 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </div>
      </div>
    </Shell>
  );
}
