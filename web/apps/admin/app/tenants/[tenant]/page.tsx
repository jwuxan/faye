import { redirect } from "next/navigation";

export default async function TenantPage({ params }: { params: Promise<{ tenant: string }> }) {
  const { tenant } = await params;
  redirect(`/tenants/${encodeURIComponent(tenant)}/flow`);
}
