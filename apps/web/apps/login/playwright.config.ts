import { defineConfig, devices } from "@playwright/test";

// Ports of their own, so a running stack (compose, deploy/dev.sh) on :3000 and :8080 is left alone
const STUB = 18080;
const LOGIN = 13000;

/**
 * Browser tests of the Login UI (ADR 0033): the production build (`pnpm build` first) against a
 * stub of Faye's screen API, in Chromium. `pnpm e2e:install` downloads the browser this
 * Playwright version pins into the git ignored .playwright/.
 */
export default defineConfig({
  testDir: "e2e",
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [["list"], ["github"]] : "list",
  use: { baseURL: `http://127.0.0.1:${LOGIN}`, trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    { command: `node e2e/stub-faye.ts ${STUB}`, port: STUB, reuseExistingServer: false },
    {
      // The standalone server, as the image runs it (Dockerfile), with the static files beside it
      command: "cp -R .next/static .next/standalone/apps/login/.next/ && node .next/standalone/apps/login/server.js",
      url: `http://127.0.0.1:${LOGIN}/error`,
      env: { FAYE_SERVER_URL: `http://127.0.0.1:${STUB}`, HOSTNAME: "127.0.0.1", PORT: String(LOGIN) },
      reuseExistingServer: false,
    },
  ],
});
