import { llmsFull } from "../../lib/docs-site";

export const dynamic = "force-dynamic";

/** Every doc in sidebar order as one Markdown file, for an agent that wants it all at once. */
export function GET() {
  return new Response(llmsFull(), { headers: { "content-type": "text/markdown; charset=utf-8" } });
}
