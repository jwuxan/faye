import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

// The sign in screens' type scale is the five text-* tokens in app/globals.css. A size outside it (an
// arbitrary text-[15px], an inline fontSize, a CSS font-size other than a token) is refused here.
// Colors, lengths and shadows are guarded for every app by packages/ui/src/tokens.test.ts.
const APP = join(import.meta.dirname, "..", "app");
const OFF_SCALE = [/text-\[[^\]]*\d(px|rem|em)\]/, /fontSize[:=]\s*[{"']?\s*\d/, /font-size:\s*\d/, /text-(2xs|[2-9]xl)\b/];

function files(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name);
    if (e.isDirectory()) return files(path);
    return /\.(tsx?|css)$/.test(e.name) ? [path] : [];
  });
}

test("the login app uses only the type scale's sizes", () => {
  const found = files(APP).flatMap((path) =>
    readFileSync(path, "utf8")
      .split("\n")
      .flatMap((line, i) => (OFF_SCALE.some((re) => re.test(line)) ? [`${path.slice(APP.length + 1)}:${i + 1}: ${line.trim()}`] : [])),
  );
  assert.deepEqual(found, []);
});
