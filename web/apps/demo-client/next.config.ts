import type { NextConfig } from "next";

const config: NextConfig = {
  // Only what the server needs at runtime, for the slim image web/Dockerfile builds.
  output: "standalone",
  // Shared components ship as TypeScript source.
  transpilePackages: ["@faye/ui"],
};

export default config;
