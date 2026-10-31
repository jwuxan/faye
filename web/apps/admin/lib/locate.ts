/** Maps a position in the YAML (from a server diagnostic) to the block it belongs to. */
import { isMap, isScalar, LineCounter, parseDocument, type YAMLMap } from "yaml";
import { isSection } from "./entries.ts";
import { addressKey, type ListKind, type Section } from "./flow.ts";

type Span = { key: string; start: number; end: number; depth: number };

/**
 * The address key (see addressKey) of the innermost step or option whose YAML contains the 1 based
 * line and column, `hook:<id>` inside a hook, or null.
 */
export function locate(text: string, line: number, column: number): string | null {
  const lines = new LineCounter();
  const doc = parseDocument(text, { lineCounter: lines });
  if (!isMap(doc.contents) || line < 1 || line > lines.lineStarts.length) return null;
  const offset = lines.lineStarts[line - 1] + Math.max(column - 1, 0);
  const spans: Span[] = [];
  for (const pair of doc.contents.items) {
    const name = isScalar(pair.key) ? String(pair.key.value) : "";
    if (name === "hooks" && isMap(pair.value)) {
      for (const h of pair.value.items) spans.push({ key: `hook:${scalar(h.key)}`, ...range(h), depth: 0 });
    }
    if (isSection(name) && isMap(pair.value)) {
      walk(pair.value.get("steps", true), name, [], "steps", 0, spans);
    }
  }
  const hits = spans.filter((s) => s.start <= offset && offset <= s.end).sort((a, b) => b.depth - a.depth);
  return hits[0]?.key ?? null;
}

function walk(list: unknown, section: Section, path: [ListKind, number][], kind: ListKind, depth: number, out: Span[]) {
  if (!isMap(list)) return;
  list.items.forEach((pair, i) => {
    const here: [ListKind, number][] = [...path, [kind, i]];
    out.push({ key: addressKey({ section, path: here }), ...range(pair), depth: depth + 1 });
    if (isMap(pair.value)) {
      const body = pair.value as YAMLMap;
      walk(body.get("alternative", true), section, here, "alternative", depth + 1, out);
      walk(body.get("steps", true), section, here, "steps", depth + 1, out);
    }
  });
}

function range(pair: { key: unknown; value: unknown }): { start: number; end: number } {
  const k = (pair.key as { range?: [number, number, number] }).range;
  const v = (pair.value as { range?: [number, number, number] } | null)?.range;
  return { start: k?.[0] ?? 0, end: v?.[1] ?? k?.[2] ?? 0 };
}

function scalar(node: unknown): string {
  return isScalar(node) ? String(node.value) : "";
}
