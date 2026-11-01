import type { ReactNode } from "react";
import { docIndex } from "../../../lib/docs";
import { adminFetch, demoClientUrl } from "../../../lib/faye";
import { sidebarOpen } from "../../../lib/sidebar";
import { Shell, type TenantView } from "../../shell";
import { DraftProvider } from "./draft";

/** Every section of one tenant shares the console frame and the tenant's draft. */
export default async function TenantLayout({ params, children }: { params: Promise<{ tenant: string }>; children: ReactNode }) {
  const { tenant } = await params;
  const res = await adminFetch("tenants");
  const tenants = res.ok ? ((await res.json()) as TenantView[]) : [{ id: tenant, current: null }];
  return (
    <Shell tenant={tenant} tenants={tenants} defaultOpen={await sidebarOpen()} docs={docIndex()}>
      <DraftProvider tenant={tenant} demoClient={demoClientUrl()}>
        {children}
      </DraftProvider>
    </Shell>
  );
}
