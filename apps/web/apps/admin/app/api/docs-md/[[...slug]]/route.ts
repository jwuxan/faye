import { loadDoc } from "../../../../lib/docs";
import { markdownOf } from "../../../../lib/docs-site";

// The docs are runtime input (FAYE_DOCS_DIR)
export const dynamic = "force-dynamic";

/**
 * A doc as Markdown, for "Copy Markdown", "View as Markdown" and agents. next.config.ts rewrites
 * /docs.md and /docs/<slug>.md here.
 */
export async function GET(_request: Request, { params }: { params: Promise<{ slug?: string[] }> }) {
  const { slug } = await params;
  const doc = loadDoc(slug ?? []);
  if (!doc) return new Response("Not found. The index of every page is at /llms.txt.\n", { status: 404, headers: { "content-type": "text/markdown; charset=utf-8" } });
  return new Response(markdownOf(doc), { headers: { "content-type": "text/markdown; charset=utf-8" } });
}
