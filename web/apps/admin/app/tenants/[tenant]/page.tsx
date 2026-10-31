import { redirect } from "next/navigation";
import { adminFetch, demoClientUrl } from "../../../lib/faye";
import { currentSession } from "../../../lib/session";
import { Editor } from "./editor";

export default async function TenantPage({ params }: { params: Promise<{ tenant: string }> }) {
  const session = await currentSession();
  if (!session) redirect("/");
  const { tenant } = await params;
  const res = await adminFetch("tenants");
  const tenants = res.ok ? ((await res.json()) as { id: string }[]).map((t) => t.id) : [tenant];
  return <Editor tenant={tenant} tenants={tenants} subject={session.sub} demoClient={demoClientUrl()} />;
}
