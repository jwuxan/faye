import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { docIndex, loadDoc } from "../../../lib/docs";
import { PageHeader } from "../../shell";
import { DocMarkdown } from "../markdown";

export async function generateMetadata({ params }: { params: Promise<{ slug?: string[] }> }): Promise<Metadata> {
  const { slug } = await params;
  const doc = loadDoc(slug ?? []);
  return { title: doc ? `${doc.title} · Faye docs` : "Faye docs" };
}

export default async function DocsPage({ params }: { params: Promise<{ slug?: string[] }> }) {
  const { slug } = await params;
  const doc = loadDoc(slug ?? []);
  if (!doc) notFound();
  const sections = docIndex();
  const current = slug === undefined || slug.length === 0 ? "/docs" : `/docs/${slug.join("/")}`;
  return (
    <>
      <PageHeader title={doc.title} />
      <div className="page-enter min-h-0 flex-1 overflow-hidden">
        <div className="mx-auto grid h-full max-w-7xl grid-cols-[minmax(0,1fr)] md:grid-cols-[auto_minmax(0,1fr)] xl:grid-cols-[auto_minmax(0,1fr)_auto]">
          <nav className="hidden w-56 shrink-0 overflow-auto border-r px-3 py-5 md:block" aria-label="Docs">
            {sections.map((section) => (
              <div key={section.title} className="mb-4">
                <p className="px-2 pb-1.5 text-xs font-medium text-muted-foreground">{section.title}</p>
                <ul className="grid gap-0.5">
                  {section.pages.map((page) => (
                    <li key={page.href}>
                      <Link
                        href={page.href}
                        className={
                          page.href === current
                            ? "block rounded-md bg-accent px-2 py-1 text-sm font-medium text-accent-foreground"
                            : "block rounded-md px-2 py-1 text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
                        }
                      >
                        {page.title}
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </nav>
          <div className="min-w-0 overflow-auto px-6 py-6">
            <article className="docs-prose mx-auto max-w-3xl">
              <DocMarkdown markdown={doc.markdown} file={doc.file} />
            </article>
            <p className="mx-auto max-w-3xl border-t pt-4 font-mono text-xs text-muted-foreground">Source: docs/{doc.file}</p>
          </div>
          {doc.toc.length > 0 && (
            <aside className="hidden w-52 shrink-0 overflow-auto px-3 py-5 xl:block" aria-label="On this page">
              <p className="px-2 pb-1.5 text-xs font-medium text-muted-foreground">On this page</p>
              <ul className="grid gap-0.5">
                {doc.toc.map((entry) => (
                  <li key={entry.id}>
                    <a
                      href={`#${entry.id}`}
                      className={entry.depth === 3 ? "block rounded-md py-1 pr-2 pl-5 text-sm text-muted-foreground hover:text-foreground" : "block rounded-md px-2 py-1 text-sm text-muted-foreground hover:text-foreground"}
                    >
                      {entry.text}
                    </a>
                  </li>
                ))}
              </ul>
            </aside>
          )}
        </div>
      </div>
    </>
  );
}
