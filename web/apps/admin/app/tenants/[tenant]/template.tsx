import type { ReactNode } from "react";

/** Remounts on each navigation inside a tenant; the page settles in fast (.page-enter) instead of cutting. */
export default function Template({ children }: { children: ReactNode }) {
  return <div className="page-enter flex min-h-0 flex-1 flex-col">{children}</div>;
}
