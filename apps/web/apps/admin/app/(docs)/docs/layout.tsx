import { FayeLogo } from "@faye/ui/components/faye-logo";
import { DocsLayout } from "fumadocs-ui/layouts/docs";
import { BotMessageSquare, LayoutDashboard } from "lucide-react";
import type { ReactNode } from "react";
import { DEEPWIKI_URL, REPO_URL } from "../../../lib/docs";
import { pageTree } from "../../../lib/docs-site";
import { ThemeSwitch } from "./theme-switch";

// The docs are runtime input (FAYE_DOCS_DIR): never prerender them at build time
export const dynamic = "force-dynamic";

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <DocsLayout
      tree={pageTree()}
      nav={{
        url: "/docs",
        title: (
          <span className="inline-flex items-center gap-2">
            <FayeLogo className="h-6 w-auto" />
            <span className="font-medium text-fd-muted-foreground">Docs</span>
          </span>
        ),
      }}
      githubUrl={REPO_URL}
      links={[
        // The console is another root layout, so following this is a full page load
        { type: "main", text: "Back to the console", url: "/", icon: <LayoutDashboard /> },
        { type: "main", text: "Ask Devin (DeepWiki)", url: DEEPWIKI_URL, icon: <BotMessageSquare />, external: true },
      ]}
      sidebar={{ defaultOpenLevel: 0 }}
      slots={{ themeSwitch: ThemeSwitch }}
    >
      {children}
    </DocsLayout>
  );
}
