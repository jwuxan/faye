import { cookies } from "next/headers";

/** Whether the console sidebar is open, as the sidebar last saved it (open when never saved). */
export async function sidebarOpen(): Promise<boolean> {
  return (await cookies()).get("sidebar_state")?.value !== "false";
}
