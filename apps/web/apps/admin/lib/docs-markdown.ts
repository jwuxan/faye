import type { Blockquote, Paragraph, Root, RootContent } from "mdast";

/**
 * GitHub's alert syntax, `> [!NOTE]` and friends, as a `Callout`. The docs stay plain Markdown
 * that GitHub renders the same way; here each alert becomes the docs site's callout.
 */
const ALERTS: Record<string, { type: string; title: string }> = {
  NOTE: { type: "info", title: "Note" },
  TIP: { type: "idea", title: "Tip" },
  IMPORTANT: { type: "warn", title: "Important" },
  WARNING: { type: "warning", title: "Warning" },
  CAUTION: { type: "error", title: "Caution" },
};

const MARKER = /^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\][ \t]*(\r?\n)?/;

/** The alert a blockquote starts with, with the marker taken off its first paragraph; or null. */
function alertOf(quote: Blockquote): { kind: string; children: RootContent[] } | null {
  const first = quote.children[0];
  if (!first || first.type !== "paragraph") return null;
  const text = first.children[0];
  if (!text || text.type !== "text") return null;
  const match = MARKER.exec(text.value);
  if (!match) return null;
  const rest = text.value.slice(match[0].length);
  const paragraph: Paragraph = { ...first, children: [...(rest ? [{ ...text, value: rest }] : []), ...first.children.slice(1)] };
  // A soft break right after the marker is all that is left of its line
  if (paragraph.children[0]?.type === "break") paragraph.children.shift();
  const children = paragraph.children.length > 0 ? [paragraph, ...quote.children.slice(1)] : quote.children.slice(1);
  return { kind: match[1], children };
}

function visit(parent: { children: RootContent[] }) {
  parent.children = parent.children.map((node) => {
    if (node.type === "blockquote") {
      const alert = alertOf(node);
      if (alert) {
        const { type, title } = ALERTS[alert.kind];
        visit({ children: alert.children });
        return {
          type: "mdxJsxFlowElement",
          name: "callout",
          attributes: [
            { type: "mdxJsxAttribute", name: "type", value: type },
            { type: "mdxJsxAttribute", name: "title", value: title },
          ],
          children: alert.children,
        } as unknown as RootContent;
      }
    }
    if ("children" in node && Array.isArray(node.children)) visit(node as { children: RootContent[] });
    return node;
  });
}

/**
 * Remark plugin: GitHub alerts (`> [!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]`, `[!CAUTION]`)
 * to a `callout` element. The renderer maps lowercase element names to components; a capitalized
 * one would need an MDX evaluator.
 */
export function remarkGithubAlerts() {
  return (tree: Root) => visit(tree);
}

/** Remark plugin: a ```mermaid fence to a `mermaid` element with the chart as its `chart` prop. */
export function remarkMermaid() {
  const walk = (parent: { children: RootContent[] }) => {
    parent.children = parent.children.map((node) => {
      if (node.type === "code" && node.lang === "mermaid") {
        return {
          type: "mdxJsxFlowElement",
          name: "mermaid",
          attributes: [{ type: "mdxJsxAttribute", name: "chart", value: node.value.trim() }],
          children: [],
        } as unknown as RootContent;
      }
      if ("children" in node && Array.isArray(node.children)) walk(node as { children: RootContent[] });
      return node;
    });
  };
  return (tree: Root) => walk(tree);
}

/** The node types the remark plugins create that remark-rehype must keep for the JSX renderer. */
export const MDX_NODE_TYPES: string[] = ["mdxJsxFlowElement", "mdxJsxTextElement"];
