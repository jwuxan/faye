import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

// The console's type scale is the four text-* tokens in app/(console)/globals.css. A size outside it (an
// arbitrary text-[13px], an inline fontSize, a CSS font-size other than a token) is refused here.
const APP = join(import.meta.dirname, "..", "app");
const OFF_SCALE = [
  /text-\[[^\]]*\d(px|rem|em)\]/,
  /fontSize[:=]\s*[{"']?\s*\d/,
  /font-size:\s*\d/,
  /text-(2xs|xl|[2-9]xl)\b/,
];

function files(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name);
    if (e.isDirectory()) return files(path);
    return /\.(tsx?|css)$/.test(e.name) ? [path] : [];
  });
}

function offending(patterns: RegExp[]): string[] {
  return files(APP)
    .flatMap((path) =>
      readFileSync(path, "utf8")
        .split("\n")
        .flatMap((line, i) => (patterns.some((re) => re.test(line)) ? [`${path.slice(APP.length + 1)}:${i + 1}: ${line.trim()}`] : [])),
    );
}

test("the admin app uses only the type scale's sizes", () => {
  assert.deepEqual(offending(OFF_SCALE), []);
});
