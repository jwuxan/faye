import type { NextConfig } from "next";

const config: NextConfig = {
  // Only what the server needs at runtime, for the slim image web/Dockerfile builds.
  output: "standalone",
  // Shared components ship as TypeScript source.
  transpilePackages: ["@faye/ui"],
  // Sign in screens are never framed (clickjacking). A host that hands off opens them in a popup.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
          { key: "X-Frame-Options", value: "DENY" },
        ],
      },
    ];
  },
};

export default config;
