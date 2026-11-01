import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

// Guards WCAG contrast of the console's token pairs: text tokens on every surface at AA 4.5,
// --foreground/--sidebar-foreground at AAA 7, filled controls' foregrounds at 4.5, and the
// non-text marks (inputs, rings, status dots, graph edges) at 1.4.11's 3:1. --border and --sidebar-border are
// deliberately exempt: they are decorative separators between same-fill regions, not component
// boundaries, so 1.4.11 does not apply to them.

const UI = join(import.meta.dirname, "..", "..", "..", "packages", "ui", "src", "globals.css");
const ADMIN = join(import.meta.dirname, "..", "app", "(console)", "globals.css");

type Tokens = Record<string, string>;

function block(css: string, selector: string): Tokens {
  const out: Tokens = {};
  let i = 0;
  while ((i = css.indexOf(selector + " {", i)) !== -1) {
    const end = css.indexOf("}", i);
    for (const m of css.slice(i, end).matchAll(/(--[\w-]+):\s*([^;]+);/g)) out[m[1]] = m[2].trim();
    i = end;
  }
  return out;
}

const ui = readFileSync(UI, "utf8");
const admin = readFileSync(ADMIN, "utf8");
const light = { ...block(ui, ":root"), ...block(admin, ":root") };
const dark = { ...light, ...block(ui, ':root[data-theme="dark"]'), ...block(admin, ':root[data-theme="dark"]') };

type RGBA = [number, number, number, number];

// oklch -> clamped gamma sRGB (OKLab to LMS to linear sRGB, per the CSS Color 4 matrices)
function oklch(v: string): RGBA {
  const m = v.match(/oklch\(([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+)%)?\)/);
  if (!m) throw new Error(`not an oklch color: ${v}`);
  const [L, C, H] = [+m[1], +m[2], (+m[3] * Math.PI) / 180];
  const a = C * Math.cos(H);
  const b = C * Math.sin(H);
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const mm = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const lin = [
    4.0767416621 * l - 3.3077115913 * mm + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * mm - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * mm + 1.707614701 * s,
  ];
  const g = lin.map((c) => {
    c = Math.min(1, Math.max(0, c));
    return c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055;
  });
  return [g[0], g[1], g[2], m[4] ? +m[4] / 100 : 1];
}

const over = (f: RGBA, b: RGBA): RGBA => [0, 1, 2].map((i) => f[i] * f[3] + b[i] * (1 - f[3])).concat(1) as RGBA;

// WCAG relative luminance of a gamma sRGB color
const lum = (c: RGBA) => {
  const [r, g, b] = c.slice(0, 3).map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};

function ratioOf(fg: RGBA, bg: RGBA): number {
  const [a, b] = [lum(over(fg, bg)), lum(bg)].sort((x, y) => y - x);
  return (a + 0.05) / (b + 0.05);
}

// fg (a token), on bg (a token), with fg's alpha scaled by `alpha`
function ratio(t: Tokens, fg: string, bg: string, alpha = 1): number {
  const F = oklch(t[fg]);
  F[3] *= alpha;
  return ratioOf(F, oklch(t[bg]));
}

// fg text on a fill that is `fill` at `fillAlpha` over `base`
function ratioOnTint(t: Tokens, fg: string, fill: string, fillAlpha: number, base: string): number {
  const F = oklch(t[fill]);
  F[3] = fillAlpha;
  return ratioOf(oklch(t[fg]), over(F, oklch(t[base])));
}

const SURFACES = [
  "--background",
  "--card",
  "--popover",
  "--sidebar",
  "--surface-2",
  "--muted",
  "--secondary",
  "--accent",
  "--sidebar-accent",
];
const TEXT = [
  "--foreground",
  "--muted-foreground",
  "--sidebar-foreground",
  "--accent-ui",
  "--primary",
  "--success",
  "--warning-text",
  "--warm",
  "--danger",
  "--destructive",
];
const TONES = ["blue", "violet", "amber", "teal", "green", "pink", "orange", "red", "slate"].map((t) => `--tone-${t}`);

test("token pairs keep WCAG contrast in both themes", () => {
  const failures: string[] = [];
  const check = (theme: string, name: string, got: number, min: number) => {
    if (got < min) failures.push(`${theme}: ${name} ${got.toFixed(2)} < ${min}`);
  };

  for (const [theme, t] of [["light", light], ["dark", dark]] as const) {
    for (const fg of TEXT)
      for (const bg of SURFACES) check(theme, `${fg} on ${bg}`, ratio(t, fg, bg), 4.5);
    for (const fg of ["--foreground", "--sidebar-foreground"])
      for (const bg of SURFACES) check(theme, `${fg} on ${bg}`, ratio(t, fg, bg), 7);
    for (const [fg, bg] of [
      ["--primary-foreground", "--primary"],
      ["--secondary-foreground", "--secondary"],
      ["--sidebar-accent-foreground", "--sidebar-accent"],
    ]) check(theme, `${fg} on ${bg}`, ratio(t, fg, bg), 4.5);
    for (const tone of TONES) check(theme, `--on-fill on ${tone}`, ratio(t, "--on-fill", tone), 4.5);
    // The destructive button's fill: solid light, 60% over --background in dark
    check(theme, "--on-fill on destructive fill", ratioOnTint(t, "--on-fill", "--destructive", theme === "dark" ? 0.6 : 1, "--background"), 4.5);
    // Text on the tinted chips and list rows drawn over --card
    const tints: [string, string, number][] = [
      ["--accent-ui", "--accent-ui", 0.16],
      ["--danger", "--danger", 0.1],
      ["--warning-text", "--warning", 0.1],
    ];
    for (const [fg, fill, alpha] of tints)
      check(theme, `${fg} on ${fill} at ${alpha * 100}% over --card`, ratioOnTint(t, fg, fill, alpha, "--card"), 4.5);
    // Non-text marks, WCAG 1.4.11's 3:1
    for (const [fg, bg] of [
      ["--input", "--background"],
      ["--ring", "--background"],
      ["--ring", "--sidebar"],
      ["--warning", "--background"],
      ["--success", "--background"],
      ["--edge", "--surface-2"],
      ["--edge", "--card"],
    ]) check(theme, `${fg} on ${bg}`, ratio(t, fg, bg), 3);
  }

  assert.deepEqual(failures, []);
});
