import { existsSync, readdirSync, readFileSync } from "node:fs";
import { basename, dirname, join, posix } from "node:path";
import GithubSlugger from "github-slugger";
import { parse } from "yaml";

// The docs directory is runtime input (FAYE_DOCS_DIR, or ../../../../docs from the app's cwd), so
// build-time path analysis can't scope it: every filesystem access below opts out of tracing.
const at = (dir: string, ...parts: string[]) => join(/* turbopackIgnore: true */ dir, ...parts);
const exists = (path: string) => existsSync(/* turbopackIgnore: true */ path);
const read = (path: string) => readFileSync(/* turbopackIgnore: true */ path, "utf8");
const list = (path: string) => readdirSync(/* turbopackIgnore: true */ path, { withFileTypes: true });

/** One sidebar section of the docs index: a title over pages, each a title and href. */
export type DocSection = { title: string; pages: { title: string; href: string }[] };
export type TocEntry = { depth: 2 | 3; text: string; id: string };
export type Doc = { title: string; markdown: string; file: string; toc: TocEntry[] };

type Nav = { sections?: { title: string; pages?: string[]; dir?: string }[] };

/** The docs directory: FAYE_DOCS_DIR in the shipped image, the repo's docs/ in dev. */
export function docsDir(): string {
  return process.env.FAYE_DOCS_DIR ?? join(process.cwd(), "../../../../docs");
}

/** A doc's slug: its path under docs/ minus .md, with index.md -> "" and <dir>/README.md -> <dir>. */
function slugOf(file: string): string {
  const noExt = file.replace(/\.md$/, "");
  if (noExt === "index") return "";
  if (basename(noExt) === "README") return dirname(noExt);
  return noExt;
}

function hrefOf(slug: string): string {
  return slug === "" ? "/docs" : `/docs/${slug}`;
}

function titleOf(markdown: string, file: string): string {
  const heading = markdown.split("\n").find((line) => line.startsWith("# "));
  return heading ? heading.slice(2).trim() : basename(file, ".md");
}

/** Every .md file under the docs directory, as a map of slug -> path relative to docs/. */
function docFiles(dir: string): Map<string, string> {
  const map = new Map<string, string>();
  const walk = (rel: string) => {
    for (const entry of list(at(dir, rel)).sort((a, b) => a.name.localeCompare(b.name))) {
      const path = rel === "" ? entry.name : `${rel}/${entry.name}`;
      if (entry.isDirectory()) walk(path);
      else if (entry.name.endsWith(".md")) map.set(slugOf(path), path);
    }
  };
  walk("");
  return map;
}

/**
 * The sidebar's sections from docs/nav.yaml: `pages` in the order given (missing files skipped),
 * `dir` as every .md directly in it, README.md first, then by name. Empty when docs/ is missing.
 */
export function docIndex(): DocSection[] {
  const dir = docsDir();
  if (!exists(dir)) return [];
  const files = docFiles(dir);
  const navFile = at(dir, "nav.yaml");
  if (!exists(navFile)) return [];
  const nav = parse(read(navFile)) as Nav;
  const page = (file: string) => {
    const slug = slugOf(file);
    if (!files.has(slug)) return null;
    return { title: titleOf(read(at(dir, file)), file), href: hrefOf(slug) };
  };
  return (nav.sections ?? []).map((section) => {
    let listed: string[];
    if (section.dir) {
      const dirName = section.dir;
      listed = list(at(dir, dirName))
        .filter((entry) => entry.isFile() && entry.name.endsWith(".md"))
        .map((entry) => entry.name)
        .sort((a, b) => (a === "README.md" ? -1 : b === "README.md" ? 1 : a.localeCompare(b)))
        .map((name) => `${dirName}/${name}`);
    } else {
      listed = section.pages ?? [];
    }
    return { title: section.title, pages: listed.map(page).filter((p) => p !== null) };
  });
}

/** Inline markdown reduced to its text: links keep their text, code and emphasis their content. */
function plainInline(text: string): string {
  return text
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/`([^`]*)`/g, "$1")
    .replace(/(\*\*|__)(.*?)\1/g, "$2")
    .replace(/(\*|_)(.*?)\1/g, "$2")
    .replace(/~~(.*?)~~/g, "$1")
    .trim();
}

/**
 * The doc at a slug (the catch-all's segments, [] for /docs), or null. `file` is its path under
 * docs/. The TOC is its ## and ### headings outside fenced code, with github-slugger ids so a
 * repeated heading gets -1 like rehype-slug gives it.
 */
export function loadDoc(slug: string[]): Doc | null {
  const dir = docsDir();
  if (!exists(dir)) return null;
  const file = docFiles(dir).get(slug.join("/"));
  if (!file) return null;
  const markdown = read(at(dir, file));
  const slugger = new GithubSlugger();
  const toc: TocEntry[] = [];
  let fence: string | null = null;
  for (const line of markdown.split("\n")) {
    const fenceMatch = line.match(/^(`{3,}|~{3,})/);
    if (fenceMatch) {
      fence = fence === null ? fenceMatch[1][0] : line.trimStart().startsWith(fence) ? null : fence;
      continue;
    }
    if (fence !== null) continue;
    const heading = line.match(/^(#{2,3})\s+(.*?)\s*#*\s*$/);
    if (heading) {
      const text = plainInline(heading[2]);
      toc.push({ depth: heading[1].length as 2 | 3, text, id: slugger.slug(text) });
    }
  }
  return { title: titleOf(markdown, file), markdown, file, toc };
}

/**
 * Where a doc's link goes. External (http(s), mailto) and bare #hash links stay as they are; a
 * relative path to a .md in docs/ (or a directory holding a README.md) becomes its /docs href,
 * keeping any #hash. Anything else — a link that leaves docs/, or not to a doc — is null.
 */
export function resolveHref(fromFile: string, href: string): string | null {
  if (/^(https?:|mailto:)/i.test(href) || href.startsWith("#")) return href;
  const [path, hash] = href.split("#", 2);
  let target: string;
  try {
    target = posix.normalize(posix.join(posix.dirname(fromFile), decodeURIComponent(path))).replace(/\/+$/, "");
  } catch {
    return null;
  }
  if (target === ".." || target.startsWith("../")) return null;
  // A directory holding a README.md resolves through the README's own slug (<dir>/README.md -> <dir>)
  const slug = slugOf(target);
  if (!docFiles(docsDir()).has(slug)) return null;
  return `${hrefOf(slug)}${hash ? `#${hash}` : ""}`;
}
