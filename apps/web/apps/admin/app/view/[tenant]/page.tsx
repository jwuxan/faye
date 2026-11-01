import type { Metadata } from "next";
import { FlowView } from "./flow-view";

export async function generateMetadata({ params }: { params: Promise<{ tenant: string }> }): Promise<Metadata> {
  const { tenant } = await params;
  return { title: `${tenant} flow · Faye` };
}

/** The flow's graph on its own page, without the console frame (like mermaid.live's /view). */
export default async function ViewPage({ params }: { params: Promise<{ tenant: string }> }) {
  const { tenant } = await params;
  return <FlowView tenant={tenant} />;
}
