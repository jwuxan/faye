import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

// Every web app and shared component names a token, never a raw value. Colors, radii and shadows
// are defined once, in globals.css, login-theme.css and each app's app/globals.css; lengths come
// from the spacing scale (a utility like max-w-90, or --spacing(n) inside an arbitrary value).
// Type sizes have their own per app guard (apps/admin/lib, apps/login/lib).
const WEB = join(import.meta.dirname, "..", "..", "..");
const ROOTS = ["packages/ui/src/components", "apps/admin/app", "apps/login/app", "apps/demo-client/app"];
const RAW: [string, RegExp][] = [
  ["raw color", /oklch\(|rgba?\(|hsla?\(|#[0-9a-fA-F]{3,8}\b/],
  ["palette color", /\b(text|bg|border|ring|fill|stroke|outline|from|to|via)-(white|black|(red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose|slate|gray|zinc|neutral|stone)-\d+)\b/],
  ["raw length in an arbitrary value", /\[[^\]"'\s]*\d(\.\d+)?(px|rem|em)\b[^\]"'\s]*\]/],
  // A named color as a value: fill="black", color={on ? "white" : ...}, { color: "red" }, color-mix(..., black 10%)
  ["named color", /\b(fill|stroke|color|stopColor|floodColor|background|backgroundColor|borderColor)\s*[=:]\s*[^;]*?["'](white|black|red|orange|yellow|green|blue|purple|pink|gray|grey|silver|navy|teal|maroon)["']|\b(white|black)\s+\d+%/],
  ["arbitrary shadow", /shadow-\[/],
  ["arbitrary letter spacing", /tracking-\[/],
];
// Next.js takes the browser's theme color as a literal; it is the login theme's white --background
const ALLOWED = [/themeColor: "#ffffff"/];

function files(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name);
    if (e.isDirectory()) return files(path);
    return /\.tsx?$/.test(e.name) && !e.name.endsWith(".test.ts") ? [path] : [];
  });
}

test("components and apps use tokens, not raw colors, lengths, shadows or letter spacing", () => {
  const found = ROOTS.flatMap((root) => files(join(WEB, root))).flatMap((path) =>
    readFileSync(path, "utf8")
      .split("\n")
      .flatMap((line, i) =>
        RAW.filter(([, re]) => re.test(line) && !ALLOWED.some((ok) => ok.test(line))).map(
          ([what]) => `${path.slice(WEB.length + 1)}:${i + 1}: ${what}: ${line.trim()}`,
        ),
      ),
  );
  assert.deepEqual(found, []);
});
