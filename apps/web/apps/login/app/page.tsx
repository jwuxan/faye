import { redirect } from "next/navigation";

/** The root has no screen of its own; /login starts a sign in when nothing named a flow. */
export default function Page() {
  redirect("/login");
}
