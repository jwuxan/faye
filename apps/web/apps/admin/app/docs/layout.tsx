import type { ReactNode } from "react";
import { docIndex } from "../../lib/docs";
import { adminFetch } from "../../lib/faye";
import { sidebarOpen } from "../../lib/sidebar";
import { Shell, type TenantView } from "../shell";

/** The docs sit outside any tenant; they still render when the Faye server is unreachable. */
export default async function DocsLayout({ children }: { children: ReactNode }) {
  let tenants: TenantView[] = [];
  try {
    const res = await adminFetch("tenants");
    if (res.ok) tenants = (await res.json()) as TenantView[];
  } catch {
    // No Faye server: the docs render anyway.
  }
  return (
    <Shell tenant={null} tenants={tenants} defaultOpen={await sidebarOpen()} docs={docIndex()}>
      {children}
    </Shell>
  );
}
