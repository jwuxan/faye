import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, posix } from "node:path";
import { test } from "node:test";
import GithubSlugger from "github-slugger";
import { parse } from "yaml";
import { docIndex, loadDoc, resolveHref } from "./docs.ts";

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
  writeFileSync(join(dir, "guide/setup.md"), "# Setup\n\nBody.\n");
  writeFileSync(join(dir, "section/README.md"), "# A section\n\nBody.\n");
  writeFileSync(join(dir, "section/other.md"), "# Other\n\nBody.\n");
  writeFileSync(
    join(dir, "nav.yaml"),
    "sections:\n  - title: Guide\n    pages: [index.md, guide/setup.md]\n  - title: More\n    dir: section\n",
  );
  return dir;
}

test("slug mapping: index -> /docs, <dir>/README.md -> <dir>, nested paths keep their path", () => {
  withDocs(fixture(), () => {
    assert.equal(loadDoc([])?.title, "Home");
    assert.equal(loadDoc(["guide", "setup"])?.file, "guide/setup.md");
    assert.equal(loadDoc(["section"])?.file, "section/README.md");
    assert.equal(loadDoc(["section", "other"])?.title, "Other");
    assert.equal(loadDoc(["nope"]), null);
  });
});

test("docIndex follows nav.yaml: pages in order, a dir lists .md with README first", () => {
  withDocs(fixture(), () => {
    assert.deepEqual(docIndex(), [
      {
        title: "Guide",
        pages: [
          { title: "Home", href: "/docs" },
          { title: "Setup", href: "/docs/guide/setup" },
        ],
      },
      {
        title: "More",
        pages: [
          { title: "A section", href: "/docs/section" },
          { title: "Other", href: "/docs/section/other" },
        ],
      },
    ]);
  });
});

test("docIndex is empty, not a throw, when the docs directory is missing", () => {
  withDocs(join(tmpdir(), "faye-docs-missing"), () => {
    assert.deepEqual(docIndex(), []);
    assert.equal(loadDoc([]), null);
  });
});

test("resolveHref: in-docs .md keeps a #hash, a dir with README resolves, outside docs is null, external and #hash pass through", () => {
  withDocs(fixture(), () => {
    assert.equal(resolveHref("index.md", "guide/setup.md#part"), "/docs/guide/setup#part");
    assert.equal(resolveHref("guide/setup.md", "../section/other.md"), "/docs/section/other");
    assert.equal(resolveHref("index.md", "section/"), "/docs/section");
    assert.equal(resolveHref("index.md", "section/README.md"), "/docs/section");
    assert.equal(resolveHref("index.md", "../outside.md"), null);
    assert.equal(resolveHref("index.md", "missing.md"), null);
    assert.equal(resolveHref("index.md", "https://example.com/x"), "https://example.com/x");
    assert.equal(resolveHref("index.md", "mailto:a@b.c"), "mailto:a@b.c");
    assert.equal(resolveHref("index.md", "#frag"), "#frag");
  });
});

test("toc: ## and ### only, fenced code ignored, inline markdown stripped, ids match github-slugger with -1 for duplicates", () => {
  const dir = mkdtempSync(join(tmpdir(), "faye-docs-"));
  writeFileSync(
    join(dir, "index.md"),
    [
      "# Title",
      "",
      "## First `code` *heading*",
      "",
      "```",
      "## not a heading",
      "```",
      "",
      "### Sub [link](https://x) one",
      "",
      "## First `code` *heading*",
      "",
      "~~~",
      "## also not",
      "~~~",
    ].join("\n"),
  );
  withDocs(dir, () => {
    const doc = loadDoc([]);
    assert(doc);
    const slugger = new GithubSlugger();
    assert.deepEqual(
      doc.toc.map((e) => ({ ...e, id: undefined })),
      [
        { depth: 2, text: "First code heading", id: undefined },
        { depth: 3, text: "Sub link one", id: undefined },
        { depth: 2, text: "First code heading", id: undefined },
      ],
    );
    assert.deepEqual(
      doc.toc.map((e) => e.id),
      doc.toc.map((e) => slugger.slug(e.text)),
    );
    assert.equal(doc.toc[2].id, `${doc.toc[0].id}-1`);
  });
});

test("the repo's docs: every nav.yaml page exists and every in-docs relative .md link resolves", () => {
  withDocs(REPO_DOCS, () => {
    const nav = parse(readFileSync(join(REPO_DOCS, "nav.yaml"), "utf8")) as { sections: { title: string; pages?: string[]; dir?: string }[] };
    const hrefs = new Set(docIndex().flatMap((s) => s.pages.map((p) => p.href)));
    for (const section of nav.sections) {
      if (section.dir) {
        assert.ok(hrefs.has(`/docs/${section.dir}`), `nav dir ${section.dir} has no README.md`);
        continue;
      }
      for (const page of section.pages ?? []) {
        const slug = page === "index.md" ? "" : page.replace(/\.md$/, "").replace(/\/README$/, "");
        assert.ok(hrefs.has(`/docs${slug === "" ? "" : `/${slug}`}`), `nav page ${page} missing from the docs index`);
        assert.ok(loadDoc(slug.split("/").filter(Boolean)), `nav page ${page} does not load`);
      }
    }

    // Every relative link in every doc that points at a .md inside docs/ must land on a real doc;
    // links that leave docs/ (../src/..., ../../AGENTS.md) are allowed to be unresolvable.
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
    for (const file of files) {
      // Links inside fenced or inline code are text, not links
      const markdown = readFileSync(join(REPO_DOCS, file), "utf8")
        .replace(/^(`{3,}|~{3,})[\s\S]*?^\1/gm, "")
        .replace(/`[^`\n]*`/g, "");
      for (const match of markdown.matchAll(/\[[^\]]*\]\(([^)\s]+)[^)]*\)/g)) {
        const href = match[1];
        if (/^(https?:|mailto:|#)/i.test(href)) continue;
        const target = posix.normalize(posix.join(posix.dirname(file), href.split("#")[0]));
        const inside = target !== ".." && !target.startsWith("../");
        const resolved = resolveHref(file, href);
        if (inside && resolved === null) dead.push(`${file}: ${href}`);
        if (resolved !== null) assert.match(resolved, /^\/docs/);
      }
    }
    assert.deepEqual(dead, []);
  });
});
