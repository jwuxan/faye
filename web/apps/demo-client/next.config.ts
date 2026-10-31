import type { NextConfig } from "next";

const config: NextConfig = {
  // Shared components ship as TypeScript source.
  transpilePackages: ["@faye/ui"],
};

export default config;
