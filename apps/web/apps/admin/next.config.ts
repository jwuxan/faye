import type { NextConfig } from "next";

const config: NextConfig = {
  // Only what the server needs at runtime, for the slim image web/Dockerfile builds.
  output: "standalone",
  // Shared components ship as TypeScript source.
  transpilePackages: ["@faye/ui"],
  // In dev, the indicator would cover the tenant switcher at the foot of the sidebar
  devIndicators: { position: "bottom-right" },
  // Tell agents where the docs' index for language models is (llmstxt.org)
  async headers() {
    return [{ source: "/docs/:path*", headers: [{ key: "Link", value: '</llms.txt>; rel="llms-txt"' }, { key: "X-Llms-Txt", value: "/llms.txt" }] }];
  },
  // Every doc as Markdown at its own URL plus .md, for agents and "Copy Markdown"
  async rewrites() {
    return [
      { source: "/docs.md", destination: "/api/docs-md" },
      { source: "/docs/:path*.md", destination: "/api/docs-md/:path*" },
    ];
  },
};

export default config;
