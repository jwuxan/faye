import { FlowPage, type FlowPageProps } from "../../flow-page";

/** /login and every path under it (ADR 0029). */
export default function Page({ searchParams }: FlowPageProps) {
  return <FlowPage searchParams={searchParams} at="/login" />;
}
