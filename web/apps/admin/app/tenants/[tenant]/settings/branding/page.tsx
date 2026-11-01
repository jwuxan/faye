import { redirect } from "next/navigation";

/** Branding moved into the Login page section (ADR 0033); old links still land there. */
export default async function Page({ params }: { params: Promise<{ tenant: string }> }) {
  const { tenant } = await params;
  redirect(`/tenants/${encodeURIComponent(tenant)}/settings/login`);
}
