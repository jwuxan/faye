import { llmsIndex } from "../../lib/docs-site";

export const dynamic = "force-dynamic";

/** The docs' index for language models (llmstxt.org): every page with its Markdown URL. */
export function GET(request: Request) {
  return new Response(llmsIndex(new URL(request.url).origin), { headers: { "content-type": "text/markdown; charset=utf-8" } });
}
