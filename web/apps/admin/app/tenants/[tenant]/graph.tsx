"use client";

import type { LucideIcon } from "lucide-react";
import { addressKey, type Entry, type FlowDoc, type ListKind, type Section } from "../../../lib/flow";
import { kindOf } from "../../../lib/kinds";

type GNode = {
  key: string;
  label: string;
  detail: string;
  icon?: LucideIcon;
  x: number;
  y: number;
  option: boolean;
  parent?: string;
  section?: boolean;
};

const W = 232;
const H = 40;
const GAP_X = 48;
const GAP_Y = 12;

/**
 * Read only picture of the flow: each section runs top to bottom, a choose branches to the right.
 * Highlights the path selected in the Paths tab.
 */
export function Graph({
  doc,
  visited,
  visitKeys,
  onSelect,
}: {
  doc: FlowDoc;
  visited: Set<string> | null;
  visitKeys: Map<string, string>;
  onSelect: (key: string) => void;
}) {
  const nodes: GNode[] = [];
  let row = 0;
  const place = (list: Entry[] | undefined, section: Section, path: [ListKind, number][], kind: ListKind, depth: number, parent?: string) => {
    let previous = parent;
    (list ?? []).forEach((e, i) => {
      const here: [ListKind, number][] = [...path, [kind, i]];
      const key = addressKey({ section, path: here });
      const look = kindOf(e.body);
      nodes.push({
        key,
        label: e.id,
        detail: [e.body.screen?.type, e.body.use].filter(Boolean).join(" · ") || look.label,
        icon: look.icon,
        x: depth * (W + GAP_X),
        y: row++ * (H + GAP_Y),
        option: kind === "alternative",
        // Steps follow one another; options all hang off their choose
        parent: kind === "steps" ? previous : parent,
      });
      if (kind === "steps") previous = key;
      place(e.body.alternative, section, here, "alternative", depth + 1, key);
      place(e.body.steps, section, here, "steps", depth + 1, key);
    });
  };
  for (const section of ["login", "consent"] as const) {
    if (!doc[section]) continue;
    const key = `section:${section}`;
    nodes.push({ key, label: section === "login" ? "Sign in" : "Consent", detail: section, x: 0, y: row++ * (H + GAP_Y), option: false, section: true });
    place(doc[section], section, [], "steps", 0, key);
    row++;
  }
  // Steps of a section sit under its header, in the first column
  const byKey = new Map(nodes.map((n) => [n.key, n]));
  const width = Math.max(...nodes.map((n) => n.x + W), W) + 2;
  const height = row * (H + GAP_Y);
  const on = (n: GNode) => !visited || n.section || visited.has(visitKeys.get(n.key) ?? "");
  const lit = (n: GNode) => !!visited && !n.section && on(n);

  return (
    <div className="h-full overflow-auto bg-surface-2 bg-[radial-gradient(var(--border)_1px,transparent_1px)] [background-size:16px_16px] p-8">
      <svg width={width} height={height} role="img" aria-label={`Graph of flow ${doc.flow}`} className="overflow-visible">
        {nodes.map((n) => {
          const p = n.parent ? byKey.get(n.parent) : undefined;
          if (!p) return null;
          const d =
            p.x === n.x
              ? `M ${p.x + 22} ${p.y + H} L ${n.x + 22} ${n.y}`
              : `M ${p.x + W} ${p.y + H / 2} C ${p.x + W + GAP_X / 2} ${p.y + H / 2}, ${n.x - GAP_X / 2} ${n.y + H / 2}, ${n.x} ${n.y + H / 2}`;
          const active = lit(n) && (p.section || lit(p));
          return <path key={`e-${n.key}`} d={d} fill="none" stroke={active ? "var(--accent-ui)" : "var(--border)"} strokeWidth={active ? 2 : 1} />;
        })}
        {nodes.map((n) =>
          n.section ? (
            <g key={n.key} transform={`translate(${n.x},${n.y})`}>
              <text x={0} y={24} fontSize={13} fontWeight={500} fill="var(--foreground)">
                {n.label}
              </text>
              <text x={n.label.length * 7.5 + 8} y={24} fontSize={12} fontFamily="var(--font-mono)" fill="var(--muted-foreground)">
                {n.detail}
              </text>
            </g>
          ) : (
            <g key={n.key} transform={`translate(${n.x},${n.y})`} opacity={on(n) ? 1 : 0.4} className="cursor-pointer" onClick={() => onSelect(n.key)}>
              <title>{`${n.label}: ${n.detail}`}</title>
              <rect width={W} height={H} rx={6} fill="var(--card)" stroke={lit(n) ? "var(--accent-ui)" : "var(--border)"} strokeWidth={lit(n) ? 1.5 : 1} />
              <rect x={10} y={8} width={24} height={24} rx={4} fill="var(--muted)" />
              {n.icon && <n.icon x={14} y={12} width={16} height={16} strokeWidth={1.75} color={lit(n) ? "var(--accent-ui)" : "var(--muted-foreground)"} />}
              <text x={42} y={18} fontSize={12} fontWeight={500} fontFamily="var(--font-mono)" fill="var(--foreground)">
                {n.label}
              </text>
              <text x={42} y={32} fontSize={11} fontFamily="var(--font-mono)" fill="var(--muted-foreground)">
                {n.detail.length > 27 ? `${n.detail.slice(0, 26)}…` : n.detail}
              </text>
              {n.option && <circle cx={0} cy={H / 2} r={3} fill="var(--card)" stroke={lit(n) ? "var(--accent-ui)" : "var(--border)"} />}
            </g>
          ),
        )}
      </svg>
    </div>
  );
}
