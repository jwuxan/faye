import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, posix } from "node:path";
import { test } from "node:test";
import type { Root } from "mdast";
import { parse } from "yaml";
import { absoluteLinks, allPages, docIndex, loadDoc, parseDoc, resolveHref } from "./docs.ts";
import { remarkGithubAlerts, remarkMermaid } from "./docs-markdown.ts";

const REPO_DOCS = join(import.meta.dirname, "..", "..", "..", "..", "..", "docs");

/** Runs fn with FAYE_DOCS_DIR pointed at dir, then restores it. */
function withDocs<T>(dir: string, fn: () => T): T {
  const saved = process.env.FAYE_DOCS_DIR;
  process.env.FAYE_DOCS_DIR = dir;
  try {
    return fn();
  } finally {
    if (saved === undefined) delete process.env.FAYE_DOCS_DIR;
    else process.env.FAYE_DOCS_DIR = saved;
  }
}

function fixture(): string {
  const dir = mkdtempSync(join(tmpdir(), "faye-docs-"));
  mkdirSync(join(dir, "guide"), { recursive: true });
  mkdirSync(join(dir, "section"), { recursive: true });
  writeFileSync(join(dir, "index.md"), "# Home\n\nSee [setup](guide/setup.md#part).\n");
  writeFileSync(join(dir, "guide/setup.md"), "---\ndescription: How to set it up.\n---\n\n# Setup\n\nBody.\n");
  writeFileSync(join(dir, "section/README.md"), "# A section\n\nBody.\n");
  writeFileSync(join(dir, "section/b.md"), "---\nstatus: accepted\ndate: 2026-11-01\n---\n\n# Bee\n\nBody.\n");
  writeFileSync(join(dir, "section/a.md"), "# Ay\n\nBody.\n");
  writeFileSync(
    join(dir, "nav.yaml"),
    "sections:\n  - title: Guide\n    pages: [index.md, guide/setup.md, guide/missing.md]\n  - title: More\n    dir: section\n",
  );
  return dir;
}

test("slug mapping: index -> /docs, <dir>/README.md -> <dir>, nested paths keep their path", () => {
  withDocs(fixture(), () => {
    assert.equal(loadDoc([])?.title, "Home");
    assert.equal(loadDoc([])?.href, "/docs");
    assert.equal(loadDoc(["guide", "setup"])?.file, "guide/setup.md");
    assert.equal(loadDoc(["section"])?.file, "section/README.md");
    assert.equal(loadDoc(["section", "a"])?.href, "/docs/section/a");
    assert.equal(loadDoc(["nope"]), null);
  });
});

test("docIndex follows nav.yaml: pages in order, missing files skipped, a dir is a folder with README as its index", () => {
  withDocs(fixture(), () => {
    const sections = docIndex();
    assert.deepEqual(sections, [
      {
        title: "Guide",
        pages: [
          { title: "Home", href: "/docs" },
          { title: "Setup", href: "/docs/guide/setup", description: "How to set it up." },
        ],
      },
      {
        title: "More",
        pages: [
          { title: "Ay", href: "/docs/section/a" },
          { title: "Bee", href: "/docs/section/b" },
        ],
        folder: { index: { title: "A section", href: "/docs/section" } },
      },
    ]);
    assert.deepEqual(
      allPages(sections).map((p) => p.href),
      ["/docs", "/docs/guide/setup", "/docs/section", "/docs/section/a", "/docs/section/b"],
    );
  });
});

test("parseDoc: front matter gives description and meta, the leading # heading is the title and leaves the body", () => {
  const doc = parseDoc("---\ndescription: One line.\nstatus: proposed\ndate: 2026-11-01\nlist: [a]\n---\n\n# The title\n\nFirst paragraph.\n", "x.md");
  assert.equal(doc.title, "The title");
  assert.equal(doc.description, "One line.");
  assert.deepEqual(doc.meta, { status: "proposed", date: "2026-11-01" });
  assert.equal(doc.body, "First paragraph.\n");
  assert.match(doc.source, /^---\n/);

  // A front matter title wins; a # heading after other content is not the title
  assert.equal(parseDoc("---\ntitle: Named\n---\n# Heading\n", "x.md").title, "Named");
  const late = parseDoc("Intro.\n\n# Later\n", "notes.md");
  assert.equal(late.title, "notes");
  assert.equal(late.body, "Intro.\n\n# Later\n");
  // Broken front matter is dropped, not shown
  assert.equal(parseDoc("---\n: [\n---\n# T\n", "x.md").title, "T");
});

test("resolveHref: docs links stay in /docs, repo files go to GitHub, other links pass or die", () => {
  withDocs(fixture(), () => {
    assert.equal(resolveHref("index.md", "guide/setup.md#part"), "/docs/guide/setup#part");
    assert.equal(resolveHref("guide/setup.md", "../section/a.md"), "/docs/section/a");
    assert.equal(resolveHref("index.md", "section/"), "/docs/section");
    assert.equal(resolveHref("index.md", "section/README.md"), "/docs/section");
    assert.equal(resolveHref("index.md", "nope.md"), null);
    assert.equal(resolveHref("guide/setup.md", "../../AGENTS.md"), "https://github.com/jwuxan/faye/blob/main/AGENTS.md");
    assert.equal(resolveHref("index.md", "../src/schema/"), "https://github.com/jwuxan/faye/tree/main/src/schema");
    assert.equal(resolveHref("index.md", "../src/a.java#L3"), "https://github.com/jwuxan/faye/blob/main/src/a.java#L3");
    assert.equal(resolveHref("index.md", "../../outside.md"), null);
    assert.equal(resolveHref("index.md", "https://example.com/x"), "https://example.com/x");
    assert.equal(resolveHref("index.md", "mailto:a@b.c"), "mailto:a@b.c");
    assert.equal(resolveHref("index.md", "#frag"), "#frag");
  });
});

test("absoluteLinks: doc links become .md URLs, repo links GitHub URLs, code and dead links stay", () => {
  withDocs(fixture(), () => {
    const md = [
      "See [setup](guide/setup.md#part), [home](index.md) and [code](../src/a.java).",
      "Keep `[inline](guide/setup.md)` and [dead](nope.md) and [web](https://x.y/z).",
      "```",
      "[fenced](guide/setup.md)",
      "```",
    ].join("\n");
    assert.equal(
      absoluteLinks(md, "index.md"),
      [
        "See [setup](/docs/guide/setup.md#part), [home](/docs.md) and [code](https://github.com/jwuxan/faye/blob/main/src/a.java).",
        "Keep `[inline](guide/setup.md)` and [dead](nope.md) and [web](https://x.y/z).",
        "```",
        "[fenced](guide/setup.md)",
        "```",
      ].join("\n"),
    );
  });
});

/** A tiny mdast for the plugin tests. */
function tree(...children: Root["children"]): Root {
  return { type: "root", children };
}

test("remarkGithubAlerts turns > [!NOTE] blockquotes into callouts and leaves other quotes alone", () => {
  const root = tree(
    {
      type: "blockquote",
      children: [{ type: "paragraph", children: [{ type: "text", value: "[!WARNING]\nMind the gap." }] }],
    },
    { type: "blockquote", children: [{ type: "paragraph", children: [{ type: "text", value: "Just a quote." }] }] },
  );
  remarkGithubAlerts()(root);
  const [alert, quote] = root.children as unknown as { type: string; name?: string; attributes?: { name: string; value: string }[]; children: unknown[] }[];
  assert.equal(alert.type, "mdxJsxFlowElement");
  assert.equal(alert.name, "callout");
  assert.deepEqual(alert.attributes?.map((a) => [a.name, a.value]), [["type", "warning"], ["title", "Warning"]]);
  assert.deepEqual(alert.children, [{ type: "paragraph", children: [{ type: "text", value: "Mind the gap." }] }]);
  assert.equal(quote.type, "blockquote");
});

test("remarkMermaid turns ```mermaid fences into mermaid elements and keeps other code", () => {
  const root = tree({ type: "code", lang: "mermaid", value: "flowchart LR\n  A --> B\n" }, { type: "code", lang: "yaml", value: "a: 1" });
  remarkMermaid()(root);
  const [diagram, code] = root.children as unknown as { type: string; name?: string; attributes?: { value: string }[] }[];
  assert.equal(diagram.name, "mermaid");
  assert.equal(diagram.attributes?.[0].value, "flowchart LR\n  A --> B");
  assert.equal(code.type, "code");
});

test("the repo's docs: every nav.yaml page exists, has a description, and every relative link resolves", () => {
  withDocs(REPO_DOCS, () => {
    const nav = parse(readFileSync(join(REPO_DOCS, "nav.yaml"), "utf8")) as { sections: { title: string; pages?: string[]; dir?: string }[] };
    const sections = docIndex();
    const hrefs = new Set(allPages(sections).map((p) => p.href));
    for (const section of nav.sections) {
      if (section.dir) {
        assert.ok(hrefs.has(`/docs/${section.dir}`), `nav dir ${section.dir} has no README.md`);
        continue;
      }
      for (const page of section.pages ?? []) {
        const slug = page === "index.md" ? "" : page.replace(/\.md$/, "").replace(/\/README$/, "");
        assert.ok(hrefs.has(`/docs${slug === "" ? "" : `/${slug}`}`), `nav page ${page} is missing`);
        const doc = loadDoc(slug.split("/").filter(Boolean));
        assert.ok(doc, `nav page ${page} does not load`);
      }
    }
    // Every guide page (not the decision records, whose front matter is MADR's) has a description
    for (const section of sections.filter((s) => !s.folder)) {
      for (const page of section.pages) assert.ok(page.description, `${page.href} has no description in its front matter`);
    }

    // Every relative link in every doc lands on a real doc, or on a repository file shown on GitHub
    const files: string[] = [];
    const walk = (rel: string) => {
      for (const entry of readdirSync(join(REPO_DOCS, rel), { withFileTypes: true })) {
        const path = rel === "" ? entry.name : `${rel}/${entry.name}`;
        if (entry.isDirectory()) walk(path);
        else if (entry.name.endsWith(".md")) files.push(path);
      }
    };
    walk("");
    const dead: string[] = [];
    const REPO = join(REPO_DOCS, "..");
    for (const file of files) {
      // Links inside fenced or inline code are text, not links
      const markdown = readFileSync(join(REPO_DOCS, file), "utf8")
        .replace(/^(`{3,}|~{3,})[\s\S]*?^\1/gm, "")
        .replace(/`[^`\n]*`/g, "");
      for (const match of markdown.matchAll(/\[[^\]]*\]\(([^)\s]+)[^)]*\)/g)) {
        const href = match[1];
        if (/^(https?:|mailto:|#)/i.test(href)) continue;
        const resolved = resolveHref(file, href);
        if (resolved === null) {
          dead.push(`${file}: ${href}`);
          continue;
        }
        // A link out of docs/ must name a file or folder that exists in the repository
        const prefix = "https://github.com/jwuxan/faye/";
        if (resolved.startsWith(prefix)) {
          const repoPath = posix.normalize(posix.join("docs", posix.dirname(file), href.split("#")[0]));
          try {
            readdirSync(join(REPO, repoPath));
          } catch {
            try {
              readFileSync(join(REPO, repoPath));
            } catch {
              dead.push(`${file}: ${href} (no such file in the repository)`);
            }
          }
        }
      }
    }
    assert.deepEqual(dead, []);
  });
});
