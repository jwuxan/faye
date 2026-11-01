import { Callout } from "fumadocs-ui/components/callout";
import { buttonVariants } from "fumadocs-ui/components/ui/button";
import { DocsBody, DocsDescription, DocsPage, DocsTitle, EditOnGitHub, MarkdownCopyButton, ViewOptionsPopover } from "fumadocs-ui/layouts/docs/page";
import defaultMdxComponents from "fumadocs-ui/mdx";
import { BotMessageSquare } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import type { AnchorHTMLAttributes } from "react";
import { DEEPWIKI_URL, loadDoc, resolveHref, type Doc } from "../../../../lib/docs";
import { markdownUrl, renderer, sourceUrls, tocOf } from "../../../../lib/docs-site";
import { Mermaid } from "../mermaid";

type Params = { params: Promise<{ slug?: string[] }> };

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { slug } = await params;
  const doc = loadDoc(slug ?? []);
  if (!doc) return { title: "Not found" };
  return { title: doc.title, ...(doc.description ? { description: doc.description } : {}) };
}

/** Links in a doc: other docs stay in the docs, repository files open on GitHub, a dead link is text. */
function linkFor(doc: Doc) {
  return function DocLink({ href, children, ...props }: AnchorHTMLAttributes<HTMLAnchorElement>) {
    const resolved = resolveHref(doc.file, href ?? "");
    if (resolved === null) return <span>{children}</span>;
    if (resolved.startsWith("/docs") || resolved.startsWith("#")) {
      return (
        <Link href={resolved} {...props}>
          {children}
        </Link>
      );
    }
    return (
      <a href={resolved} target="_blank" rel="noreferrer noopener" {...props}>
        {children}
      </a>
    );
  };
}

/** Front matter worth showing under the title: an ADR's status and date. */
function MetaLine({ meta }: { meta: Record<string, string> }) {
  const items = [meta.status && `Status: ${meta.status}`, meta.date && `Date: ${meta.date}`].filter(Boolean);
  if (items.length === 0) return null;
  return (
    <p className="docs-meta">
      {items.map((item) => (
        <span key={item}>{item}</span>
      ))}
    </p>
  );
}

export default async function DocPage({ params }: Params) {
  const { slug } = await params;
  const doc = loadDoc(slug ?? []);
  if (!doc) notFound();
  const toc = await tocOf(doc);
  const source = sourceUrls(doc);
  const { Markdown } = renderer;
  return (
    <DocsPage toc={toc} tableOfContent={{ style: "clerk" }}>
      <DocsTitle>{doc.title}</DocsTitle>
      {doc.description && <DocsDescription className="mb-0">{doc.description}</DocsDescription>}
      <MetaLine meta={doc.meta} />
      <div className="flex flex-row flex-wrap items-center gap-2 border-b pt-2 pb-6">
        <MarkdownCopyButton markdownUrl={markdownUrl(doc)} />
        {/* The console runs on localhost, so AI assistants read the page from GitHub instead */}
        <ViewOptionsPopover markdownUrl={markdownUrl(doc)} githubUrl={source.github} pageUrl={source.raw} />
        <a
          href={DEEPWIKI_URL}
          target="_blank"
          rel="noreferrer noopener"
          className={buttonVariants({ variant: "secondary", size: "sm", className: "gap-2 [&_svg]:size-3.5 [&_svg]:text-fd-muted-foreground" })}
        >
          <BotMessageSquare />
          Ask Devin
        </a>
      </div>
      <DocsBody>
        <Markdown async components={{ ...defaultMdxComponents, a: linkFor(doc), ...({ callout: Callout, mermaid: Mermaid } as object) }}>
          {doc.body}
        </Markdown>
      </DocsBody>
      <EditOnGitHub href={source.github.replace("/blob/", "/edit/")} />
    </DocsPage>
  );
}
