import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { basename, dirname, join, posix } from "node:path";
import { parse } from "yaml";

// The docs directory is runtime input (FAYE_DOCS_DIR, or ../../../../docs from the app's cwd), so
// build-time path analysis can't scope it: every filesystem access below opts out of tracing.
const at = (dir: string, ...parts: string[]) => join(/* turbopackIgnore: true */ dir, ...parts);
const exists = (path: string) => existsSync(/* turbopackIgnore: true */ path);
const read = (path: string) => readFileSync(/* turbopackIgnore: true */ path, "utf8");
const list = (path: string) => readdirSync(/* turbopackIgnore: true */ path, { withFileTypes: true });
const mtime = (path: string) => statSync(/* turbopackIgnore: true */ path).mtimeMs;

/** Where the repository lives on GitHub: the docs link repo files there, and the page actions use it. */
export const REPO_URL = "https://github.com/jwuxan/faye";
export const REPO_BRANCH = "main";
/** DeepWiki (by Devin) answers questions about the public repository: the docs' "Ask Devin" link. */
export const DEEPWIKI_URL = REPO_URL.replace("https://github.com/", "https://deepwiki.com/");

/** One page of the docs index. */
export type DocLink = { title: string; href: string; description?: string };
/** One sidebar section: a title over pages. A `dir` section is a folder; its README is `index`. */
export type DocSection = { title: string; pages: DocLink[]; folder?: { index: DocLink | null } };
/** A doc: its title and description (front matter, else the first `# ` heading), and its Markdown body. */
export type Doc = {
  title: string;
  description?: string;
  /** Front matter fields other than title and description (an ADR's status and date). */
  meta: Record<string, string>;
  /** The full file, front matter included: what "Copy Markdown" gives an agent. */
  source: string;
  /** The body without front matter and without its `# ` title. */
  body: string;
  file: string;
  href: string;
};

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

export function hrefOf(slug: string): string {
  return slug === "" ? "/docs" : `/docs/${slug}`;
}

const FRONT_MATTER = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/;

/** Splits a doc into its front matter fields and its body. Fields that are not scalars are dropped. */
function frontMatter(markdown: string): { fields: Record<string, string>; rest: string } {
  const match = FRONT_MATTER.exec(markdown);
  if (!match) return { fields: {}, rest: markdown };
  let parsed: unknown;
  try {
    parsed = parse(match[1]);
  } catch {
    return { fields: {}, rest: markdown.slice(match[0].length) };
  }
  const fields: Record<string, string> = {};
  if (parsed && typeof parsed === "object") {
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      if (["string", "number", "boolean"].includes(typeof value)) fields[key] = String(value);
      else if (value instanceof Date) fields[key] = value.toISOString().slice(0, 10);
    }
  }
  return { fields, rest: markdown.slice(match[0].length) };
}

/** Parses a doc file. The first `# ` heading is the title unless front matter names one, and leaves the body. */
export function parseDoc(markdown: string, file: string): Omit<Doc, "href"> {
  const { fields, rest } = frontMatter(markdown);
  const { title: fmTitle, description, ...meta } = fields;
  const lines = rest.split("\n");
  const h1 = lines.findIndex((line) => line.startsWith("# "));
  // Only a heading before any other content is the page's title
  const leading = h1 >= 0 && lines.slice(0, h1).every((line) => line.trim() === "");
  const title = fmTitle ?? (leading ? lines[h1].slice(2).trim() : basename(file, ".md"));
  const body = leading ? lines.slice(h1 + 1).join("\n").replace(/^\s*\n/, "") : rest;
  return { title, description, meta, source: markdown, body, file };
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
 * `dir` as a folder of every .md directly in it, README.md as its index, the rest by name. Empty
 * when docs/ is missing.
 */
export function docIndex(): DocSection[] {
  const dir = docsDir();
  if (!exists(dir)) return [];
  const files = docFiles(dir);
  const navFile = at(dir, "nav.yaml");
  if (!exists(navFile)) return [];
  const nav = parse(read(navFile)) as Nav;
  const page = (file: string): DocLink | null => {
    const slug = slugOf(file);
    if (!files.has(slug)) return null;
    const { title, description } = parseDoc(read(at(dir, file)), file);
    return { title, href: hrefOf(slug), ...(description ? { description } : {}) };
  };
  return (nav.sections ?? []).map((section) => {
    if (section.dir) {
      const dirName = section.dir;
      const names = list(at(dir, dirName))
        .filter((entry) => entry.isFile() && entry.name.endsWith(".md"))
        .map((entry) => entry.name)
        .sort((a, b) => a.localeCompare(b));
      const index = names.includes("README.md") ? page(`${dirName}/README.md`) : null;
      const pages = names
        .filter((name) => name !== "README.md")
        .map((name) => page(`${dirName}/${name}`))
        .filter((p) => p !== null);
      return { title: section.title, pages, folder: { index } };
    }
    return { title: section.title, pages: (section.pages ?? []).map(page).filter((p) => p !== null) };
  });
}

/** Every page of the index in sidebar order, folder indexes before their pages. */
export function allPages(sections: DocSection[] = docIndex()): DocLink[] {
  return sections.flatMap((s) => [...(s.folder?.index ? [s.folder.index] : []), ...s.pages]);
}

/** The doc at a slug (the catch-all's segments, [] for /docs), or null. `file` is its path under docs/. */
export function loadDoc(slug: string[]): Doc | null {
  const dir = docsDir();
  if (!exists(dir)) return null;
  const key = slug.join("/");
  const file = docFiles(dir).get(key);
  if (!file) return null;
  return { ...parseDoc(read(at(dir, file)), file), href: hrefOf(key) };
}

/** A fingerprint of the docs directory that changes when any doc changes: caches key on it. */
export function docsVersion(): string {
  const dir = docsDir();
  if (!exists(dir)) return "none";
  const files = [...docFiles(dir).values()];
  return `${files.length}:${Math.max(0, ...files.map((f) => mtime(at(dir, f))), exists(at(dir, "nav.yaml")) ? mtime(at(dir, "nav.yaml")) : 0)}`;
}

/** Where a repository file outside docs/ is shown: on GitHub, at the default branch. */
export function githubUrl(repoPath: string, kind: "blob" | "tree" = "blob"): string {
  return `${REPO_URL}/${kind}/${REPO_BRANCH}/${repoPath}`;
}

/**
 * Where a doc's link goes. External (http(s), mailto) and bare #hash links stay as they are; a
 * relative path to a .md in docs/ (or a directory holding a README.md) becomes its /docs href,
 * keeping any #hash. A relative path that leaves docs/ but stays in the repository (../src/...,
 * ../AGENTS.md) goes to that file on GitHub. Anything else, a path that leaves the repository or
 * names no doc inside docs/, is null.
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
  if (target === ".." || target.startsWith("../")) {
    // docs/ sits at the repository root, so one more ../ than that leaves the repository
    const repoPath = target.replace(/^\.\.\/?/, "");
    if (repoPath === "" || repoPath === ".." || repoPath.startsWith("../")) return null;
    // A link that ends with a slash names a folder; anything else, a file (Dockerfile, Makefile)
    const kind = path.endsWith("/") ? "tree" : "blob";
    return `${githubUrl(repoPath, kind)}${hash ? `#${hash}` : ""}`;
  }
  // A directory holding a README.md resolves through the README's own slug (<dir>/README.md -> <dir>)
  const slug = slugOf(target);
  if (!docFiles(docsDir()).has(slug)) return null;
  return `${hrefOf(slug)}${hash ? `#${hash}` : ""}`;
}

/**
 * A doc's Markdown with its relative links made absolute, for an agent that reads one page out of
 * context: links to other docs point at their Markdown (/docs/<slug>.md), links to repository files
 * at GitHub. Links in fenced or inline code, and links that resolve to nothing, stay as written.
 */
export function absoluteLinks(markdown: string, fromFile: string): string {
  const toMarkdownUrl = (href: string) => {
    const [path, hash] = href.split("#", 2);
    return `${path === "/docs" ? "/docs.md" : `${path}.md`}${hash ? `#${hash}` : ""}`;
  };
  const rewrite = (text: string) =>
    text.replace(/(\]\()([^)\s]+)((?:\s+"[^"]*")?\))/g, (whole, open: string, href: string, close: string) => {
      if (/^(https?:|mailto:|#)/i.test(href)) return whole;
      const resolved = resolveHref(fromFile, href);
      if (resolved === null) return whole;
      return `${open}${resolved.startsWith("/docs") ? toMarkdownUrl(resolved) : resolved}${close}`;
    });
  // Split on fenced blocks and inline code, and rewrite only the prose between them
  return markdown
    .split(/(^(?:`{3,}|~{3,})[^\n]*\n[\s\S]*?^(?:`{3,}|~{3,})[ \t]*$)/m)
    .map((part, i) => (i % 2 === 1 ? part : part.split(/(`[^`\n]*`)/).map((p, j) => (j % 2 === 1 ? p : rewrite(p))).join("")))
    .join("");
}
