import Link from "next/link";
import { Fragment, type ReactNode } from "react";
import Markdown, { type Components } from "react-markdown";
import rehypeSlug from "rehype-slug";
import remarkGfm from "remark-gfm";
import { resolveHref } from "../../lib/docs";
import { highlightLines, type HighlightLang } from "../../lib/highlight";

const HIGHLIGHTABLE: Record<string, HighlightLang> = {
  yaml: "yaml",
  yml: "yaml",
  js: "js",
  javascript: "javascript",
  ts: "ts",
  typescript: "typescript",
  json: "json",
};

function textOf(children: ReactNode): string {
  return Array.isArray(children) ? children.join("") : String(children ?? "");
}

/** A heading with a # anchor that shows on hover; rehype-slug set its id. */
function anchored(Tag: "h2" | "h3") {
  return function Heading({ id, children }: { id?: string; children?: ReactNode }) {
    return (
      <Tag id={id}>
        {children}
        {id && (
          <a className="docs-anchor" href={`#${id}`} aria-hidden="true">
            #
          </a>
        )}
      </Tag>
    );
  };
}

/**
 * One doc rendered from its markdown. Links into docs/ become console links, external links open
 * in a new tab, and a link that resolves to nothing readable renders as plain text. Fenced yaml,
 * js/ts and json are highlighted like the editors; raw HTML in a doc is escaped, never rendered.
 */
export function DocMarkdown({ markdown, file }: { markdown: string; file: string }) {
  const components: Components = {
    a({ href, children }) {
      const resolved = resolveHref(file, href ?? "");
      if (resolved === null) return <span>{children}</span>;
      if (resolved.startsWith("/docs")) return <Link href={resolved}>{children}</Link>;
      if (resolved.startsWith("#")) return <a href={resolved}>{children}</a>;
      return (
        <a href={resolved} target="_blank" rel="noreferrer">
          {children}
        </a>
      );
    },
    code({ className, children }) {
      const lang = /language-(\w+)/.exec(className ?? "")?.[1]?.toLowerCase();
      const highlight = lang ? HIGHLIGHTABLE[lang] : undefined;
      if (!highlight) return <code className={className}>{children}</code>;
      const lines = highlightLines(textOf(children), highlight);
      return (
        <code className={className}>
          {lines.map((line, i) => (
            <Fragment key={i}>
              {i > 0 && "\n"}
              {line.map((token, j) =>
                token.cls ? (
                  <span key={j} className={token.cls}>
                    {token.text}
                  </span>
                ) : (
                  token.text
                ),
              )}
            </Fragment>
          ))}
        </code>
      );
    },
    h2: anchored("h2"),
    h3: anchored("h3"),
  };
  return (
    <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeSlug]} components={components}>
      {markdown}
    </Markdown>
  );
}
