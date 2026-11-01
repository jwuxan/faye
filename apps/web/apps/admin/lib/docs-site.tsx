import { createMarkdownRenderer, type MarkdownRendererOptions } from "fumadocs-core/content/md";
import { rehypeCode, remarkGfm, remarkHeading, remarkSteps } from "fumadocs-core/mdx-plugins";
import { structure } from "fumadocs-core/mdx-plugins/remark-structure";
import type * as PageTree from "fumadocs-core/page-tree";
import type { AdvancedIndex } from "fumadocs-core/search/server";
import type { TOCItemType } from "fumadocs-core/toc";
import { remark } from "remark";
import { absoluteLinks, allPages, docIndex, docsVersion, loadDoc, REPO_BRANCH, REPO_URL, type Doc, type DocSection } from "./docs";
import { MDX_NODE_TYPES, remarkGithubAlerts, remarkMermaid } from "./docs-markdown";

/**
 * The remark plugins every doc goes through, in order. Heading ids come before steps, so a step
 * heading ("### 6. Add a factor") keeps the id GitHub gives it ("6-add-a-factor"), and a link to it
 * works on GitHub and here alike.
 */
const REMARK = [remarkGfm, remarkGithubAlerts, remarkMermaid, remarkHeading, remarkSteps];

/**
 * The docs' Markdown renderer: GitHub flavored Markdown, GitHub alerts as callouts, ```mermaid as
 * diagrams, `### 1. Title` headings as steps, heading anchors, and Shiki highlighting in a light
 * and a dark theme. Raw HTML in a doc is not rendered.
 */
export const renderer = createMarkdownRenderer({
  remarkPlugins: REMARK,
  remarkRehypeOptions: { passThrough: MDX_NODE_TYPES } as MarkdownRendererOptions["remarkRehypeOptions"],
  rehypePlugins: [[rehypeCode, { themes: { light: "github-light", dark: "github-dark" }, fallbackLanguage: "text" }]],
});

/**
 * A doc's table of contents: its ## and ### headings, with the ids the renderer gives them. It runs
 * the renderer's own remark plugins, and reads the headings without writing the tree back out.
 */
export async function tocOf(doc: Doc): Promise<TOCItemType[]> {
  let toc: TOCItemType[] = [];
  const processor = remark()
    .use(REMARK)
    .use(() => (_tree, file) => {
      toc = (file.data.toc as TOCItemType[] | undefined) ?? [];
    });
  await processor.run(processor.parse(doc.body));
  return toc.filter((item) => item.depth >= 2 && item.depth <= 3);
}

/** The sidebar: one separator per nav.yaml section over its pages; a `dir` section is a folder. */
export function pageTree(sections: DocSection[] = docIndex()): PageTree.Root {
  const page = (p: { title: string; href: string; description?: string }): PageTree.Item => ({
    type: "page",
    name: p.title,
    url: p.href,
    ...(p.description ? { description: p.description } : {}),
  });
  const children: PageTree.Node[] = [];
  for (const section of sections) {
    if (section.folder) {
      children.push({
        type: "folder",
        name: section.title,
        defaultOpen: false,
        ...(section.folder.index ? { index: page(section.folder.index) } : {}),
        children: section.pages.map(page),
      });
    } else {
      children.push({ type: "separator", name: section.title }, ...section.pages.map(page));
    }
  }
  return { name: "Faye docs", children };
}

/** The doc's source file on GitHub, and the raw file an AI assistant can read. */
export function sourceUrls(doc: Doc): { github: string; raw: string } {
  return {
    github: `${REPO_URL}/blob/${REPO_BRANCH}/docs/${doc.file}`,
    raw: `${REPO_URL.replace("https://github.com/", "https://raw.githubusercontent.com/")}/${REPO_BRANCH}/docs/${doc.file}`,
  };
}

/** Where a doc's Markdown is served: /docs.md for the home page, /docs/<slug>.md for the rest. */
export function markdownUrl(doc: Doc): string {
  return `${doc.href}.md`;
}

/** A doc as plain Markdown for an agent: a title, where it lives, and its body. */
export function markdownOf(doc: Doc): string {
  const lines = [`# ${doc.title}`, ""];
  if (doc.description) lines.push(`> ${doc.description}`, "");
  lines.push(`Source: ${sourceUrls(doc).github}`, "", absoluteLinks(doc.body, doc.file).trim(), "");
  return lines.join("\n");
}

let searchCache: { version: string; indexes: AdvancedIndex[] } | null = null;

/** The search index of every doc in the sidebar, rebuilt when a doc changes. */
export function searchIndexes(): AdvancedIndex[] {
  const version = docsVersion();
  if (searchCache?.version === version) return searchCache.indexes;
  const sections = docIndex();
  const indexes: AdvancedIndex[] = [];
  for (const section of sections) {
    for (const link of [...(section.folder?.index ? [section.folder.index] : []), ...section.pages]) {
      const doc = loadDoc(link.href.replace(/^\/docs\/?/, "").split("/").filter(Boolean));
      if (!doc) continue;
      indexes.push({
        id: doc.href,
        title: doc.title,
        ...(doc.description ? { description: doc.description } : {}),
        breadcrumbs: [section.title],
        url: doc.href,
        structuredData: structure(doc.body, [remarkGfm]),
      });
    }
  }
  searchCache = { version, indexes };
  return indexes;
}

/** What /llms.txt says Faye is: the docs home page's own description, so the two never drift. */
function summary(): string {
  return loadDoc([])?.description ?? "Faye is an embeddable, configurable flow runtime built only for sign in and sign up.";
}

/** /llms.txt: a title, a summary, then each sidebar section as a list of its pages' Markdown URLs. */
export function llmsIndex(origin: string): string {
  const lines = ["# Faye", "", `> ${summary()}`, ""];
  for (const section of docIndex()) {
    const pages = [...(section.folder?.index ? [section.folder.index] : []), ...section.pages];
    lines.push(`## ${section.folder ? "Optional" : section.title}`, "");
    if (section.folder) lines.push(`${section.title}:`, "");
    for (const p of pages) lines.push(`- [${p.title}](${origin}${p.href}.md)${p.description ? `: ${p.description}` : ""}`);
    lines.push("");
  }
  return lines.join("\n");
}

/**
 * /llms-full.txt: every guide and reference page of the sidebar as Markdown, in sidebar order. The
 * decision records (a `dir` section) are left out: they would make the file too large for one
 * context, and /llms.txt lists them under Optional.
 */
export function llmsFull(): string {
  return allPages(docIndex().filter((section) => !section.folder))
    .map((p) => loadDoc(p.href.replace(/^\/docs\/?/, "").split("/").filter(Boolean)))
    .filter((d) => d !== null)
    .map(markdownOf)
    .join("\n---\n\n");
}
