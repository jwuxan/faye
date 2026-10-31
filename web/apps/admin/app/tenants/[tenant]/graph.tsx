"use client";

import { ButtonGroup } from "@faye/ui/components/button-group";
import { Button } from "@faye/ui/components/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@faye/ui/components/tooltip";
import { cn } from "@faye/ui/lib/utils";
import { CircleCheck, type LucideIcon, Maximize, Minus, Plus } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { CONSENT, ENTRIES, SECTIONS } from "../../../lib/entries";
import { addressKey, consentStart, type Entry, type FlowDoc, type ListKind, type Section } from "../../../lib/flow";
import { kindOf } from "../../../lib/kinds";
import type { Findings } from "./tree";

type GNode = {
  key: string;
  label: string;
  detail: string;
  icon?: LucideIcon;
  x: number;
  y: number;
  option: boolean;
  parent?: string;
  kind: "entry" | "section" | "end";
  /** What the block says, without its nested lists: a change here flashes the node. */
  sign: string;
};

const W = 184;
const H = 40;
const GAP_X = 32;
const GAP_Y = 12;
const PAD = 24;
const ZOOMS = [0.5, 0.67, 0.8, 0.9, 1, 1.1, 1.25, 1.5];

/** Lays the flow out: each section runs top to bottom, a choose or group branches to the right. */
function layout(doc: FlowDoc): { nodes: GNode[]; width: number; height: number } {
  const nodes: GNode[] = [];
  let row = 0;
  const place = (list: Entry[] | undefined, section: Section, path: [ListKind, number][], kind: ListKind, depth: number, parent?: string, from = 0, to = Infinity): string | undefined => {
    let previous = parent;
    (list ?? []).forEach((e, i) => {
      if (i < from || i >= to) return;
      const here: [ListKind, number][] = [...path, [kind, i]];
      const key = addressKey({ section, path: here });
      const look = kindOf(e.body);
      const { alternative, steps, ...own } = e.body;
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
        kind: "entry",
        sign: JSON.stringify([e.id, own, alternative?.length, steps?.length]),
      });
      if (kind === "steps") previous = key;
      place(alternative, section, here, "alternative", depth + 1, key);
      place(steps, section, here, "steps", depth + 1, key);
    });
    return previous;
  };
  const end = (key: string, label: string, parent: string | undefined) => {
    nodes.push({ key, label, detail: "", x: 0, y: row++ * (H + GAP_Y), option: false, parent, kind: "end", sign: "" });
    row++;
    return key;
  };
  for (const section of SECTIONS) {
    const list = doc[section];
    const key = `section:${section}`;
    nodes.push({ key, label: ENTRIES[section].title, detail: section, x: 0, y: row++ * (H + GAP_Y), option: false, kind: "section", sign: "" });
    // The sign in ends where consent starts; the consent steps then run on their own challenge
    const split = consentStart(list);
    const signedIn = end(`end:${section}`, ENTRIES[section].done, place(list, section, [], "steps", 0, key, 0, split));
    if (split < list.length) end(`end:${CONSENT.kind}`, CONSENT.done, place(list, section, [], "steps", 0, signedIn, split));
  }
  const width = Math.max(...nodes.map((n) => n.x + W), W);
  const height = Math.max(row - 1, 1) * (H + GAP_Y);
  return { nodes, width, height };
}

/**
 * The flow's shape, drawn from the same document the blocks edit, so every edit shows here at once.
 * Clicking a node selects its block; the selected block, the highlighted path and recent changes are
 * marked.
 */
export function Graph({
  doc,
  visited,
  visitKeys,
  selected,
  onSelect,
  findings,
}: {
  doc: FlowDoc;
  visited: Set<string> | null;
  visitKeys: Map<string, string>;
  selected: string | null;
  onSelect: (key: string) => void;
  findings: Findings;
}) {
  const { nodes, width, height } = useMemo(() => layout(doc), [doc]);
  const byKey = useMemo(() => new Map(nodes.map((n) => [n.key, n])), [nodes]);
  const [zoom, setZoom] = useState(1);
  const scroller = useRef<HTMLDivElement>(null);
  const els = useRef(new Map<string, SVGGElement>());

  // Nodes whose block changed in the last edit flash once
  const signs = useRef<Map<string, string> | null>(null);
  const [flashing, setFlashing] = useState<Set<string>>(new Set());
  useEffect(() => {
    const next = new Map(nodes.filter((n) => n.kind === "entry").map((n) => [n.key, n.sign]));
    const before = signs.current;
    signs.current = next;
    if (!before) return;
    const changed = new Set([...next].filter(([k, s]) => before.get(k) !== s).map(([k]) => k));
    if (changed.size === 0 || changed.size === next.size) return;
    setFlashing(changed);
    const timer = setTimeout(() => setFlashing(new Set()), 1400);
    return () => clearTimeout(timer);
  }, [nodes]);

  // Keep the selected block in view
  useEffect(() => {
    if (selected) els.current.get(selected)?.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" });
  }, [selected]);

  // Fit the width, and keep fitting as the panel resizes until the author zooms by hand
  const [auto, setAuto] = useState(true);
  useEffect(() => {
    const box = scroller.current;
    if (!box || !auto) return;
    const fit = () => setZoom(Math.max(ZOOMS[0], Math.min(1, Math.floor(((box.clientWidth - 8) / (width + PAD * 2)) * 100) / 100)));
    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(box);
    return () => observer.disconnect();
  }, [auto, width]);
  const step = (dir: 1 | -1) => {
    setAuto(false);
    const i = ZOOMS.findIndex((z) => z >= zoom - 0.001);
    const at = i === -1 ? ZOOMS.length - 1 : ZOOMS[i] === zoom ? i : dir > 0 ? i - 1 : i;
    setZoom(ZOOMS[Math.max(0, Math.min(ZOOMS.length - 1, at + dir))]);
  };

  const on = (n: GNode) => !visited || n.kind !== "entry" || visited.has(visitKeys.get(n.key) ?? "");
  const lit = (n: GNode) => !!visited && n.kind === "entry" && on(n);

  return (
    <div className="relative h-full min-h-0">
      <div
        ref={scroller}
        className="h-full overflow-auto bg-surface-2 bg-[radial-gradient(var(--border)_1px,transparent_1px)] [background-size:16px_16px]"
      >
        <svg
          width={(width + PAD * 2) * zoom}
          height={(height + PAD * 2) * zoom}
          viewBox={`${-PAD} ${-PAD} ${width + PAD * 2} ${height + PAD * 2}`}
          role="img"
          aria-label={`Graph of flow ${doc.flow}`}
          className="block"
        >
          {nodes.map((n) => {
            const p = n.parent ? byKey.get(n.parent) : undefined;
            if (!p) return null;
            const d =
              p.x === n.x
                ? `M ${p.x + 22} ${p.y + (p.kind === "section" ? 30 : p.kind === "end" ? 32 : H)} L ${n.x + 22} ${n.y + (n.kind === "end" ? 8 : 0)}`
                : `M ${p.x + W} ${p.y + H / 2} C ${p.x + W + GAP_X / 2} ${p.y + H / 2}, ${n.x - GAP_X / 2} ${n.y + H / 2}, ${n.x} ${n.y + H / 2}`;
            const active = !!visited && (n.kind === "end" ? lit(p) : lit(n) && (p.kind !== "entry" || lit(p)));
            return (
              <path
                key={`e-${n.key}`}
                d={d}
                fill="none"
                stroke={active ? "var(--accent-ui)" : "var(--border)"}
                strokeWidth={active ? 2 : 1.25}
                className="transition-[stroke] duration-300"
              />
            );
          })}
          {nodes.map((n) => {
            if (n.kind === "section") {
              return (
                <g key={n.key} transform={`translate(${n.x},${n.y})`}>
                  <text x={0} y={24} fontSize={13} fontWeight={600} fill="var(--foreground)">
                    {n.label}
                  </text>
                  <text x={n.label.length * 7.6 + 8} y={24} fontSize={11} fontFamily="var(--font-mono)" fill="var(--muted-foreground)">
                    {n.detail}
                  </text>
                </g>
              );
            }
            if (n.kind === "end") {
              return (
                <g key={n.key} transform={`translate(${n.x},${n.y + 8})`} opacity={visited ? 0.5 : 1}>
                  <rect width={96} height={24} rx={12} fill="var(--card)" stroke="var(--border)" />
                  <CircleCheck x={8} y={5} width={14} height={14} strokeWidth={1.75} color="var(--success)" />
                  <text x={27} y={16} fontSize={11} fontFamily="var(--font-mono)" fill="var(--muted-foreground)">
                    {n.label}
                  </text>
                </g>
              );
            }
            const isSelected = selected === n.key;
            const flash = flashing.has(n.key);
            const found = findings.get(n.key);
            const accent = isSelected || lit(n);
            return (
              <g
                key={n.key}
                ref={(el) => {
                  if (el) els.current.set(n.key, el);
                  else els.current.delete(n.key);
                }}
                transform={`translate(${n.x},${n.y})`}
                opacity={on(n) ? 1 : 0.35}
                className="cursor-pointer outline-none [&:focus-visible>rect:first-of-type]:stroke-[var(--ring)]"
                role="button"
                tabIndex={-1}
                aria-label={`${n.label}: ${n.detail}`}
                aria-pressed={isSelected}
                onClick={() => onSelect(n.key)}
              >
                <title>{`${n.label}: ${n.detail}`}</title>
                <rect
                  width={W}
                  height={H}
                  rx={6}
                  fill={isSelected ? "color-mix(in oklch, var(--accent-ui) 8%, var(--card))" : "var(--card)"}
                  stroke={accent ? "var(--accent-ui)" : "var(--border)"}
                  strokeWidth={isSelected ? 2 : accent ? 1.5 : 1}
                  className="transition-[stroke,fill] duration-200"
                />
                {flash && <rect width={W} height={H} rx={6} fill="none" stroke="var(--accent-ui)" strokeWidth={2} className="graph-flash" />}
                <rect x={8} y={8} width={24} height={24} rx={4} fill="var(--muted)" />
                {n.icon && <n.icon x={12} y={12} width={16} height={16} strokeWidth={1.75} color={accent ? "var(--accent-ui)" : "var(--muted-foreground)"} />}
                <text x={40} y={18} fontSize={12} fontWeight={500} fontFamily="var(--font-mono)" fill="var(--foreground)">
                  {n.label.length > 19 ? `${n.label.slice(0, 18)}…` : n.label}
                </text>
                <text x={40} y={32} fontSize={11} fontFamily="var(--font-mono)" fill="var(--muted-foreground)">
                  {n.detail.length > 21 ? `${n.detail.slice(0, 20)}…` : n.detail}
                </text>
                {found && (found.errors.length > 0 || found.warnings.length > 0) && (
                  <circle cx={W - 10} cy={10} r={3.5} fill={found.errors.length > 0 ? "var(--danger)" : "var(--warning)"} />
                )}
                {n.option && <circle cx={0} cy={H / 2} r={3} fill="var(--card)" stroke={accent ? "var(--accent-ui)" : "var(--border)"} />}
              </g>
            );
          })}
        </svg>
      </div>
      <ButtonGroup className="absolute right-3 bottom-3 rounded-md bg-background shadow-sm" aria-label="Zoom">
        <ZoomButton label="Zoom out" onClick={() => step(-1)} disabled={zoom <= ZOOMS[0]}>
          <Minus />
        </ZoomButton>
        <Button variant="outline" size="sm" className="h-7 w-12 px-0 font-mono text-[11px] tabular-nums" onClick={() => {
            setAuto(false);
            setZoom(1);
          }} aria-label="Reset zoom">
          {Math.round(zoom * 100)}%
        </Button>
        <ZoomButton label="Zoom in" onClick={() => step(1)} disabled={zoom >= ZOOMS[ZOOMS.length - 1]}>
          <Plus />
        </ZoomButton>
        <ZoomButton label="Fit width" onClick={() => setAuto(true)} active={auto}>
          <Maximize />
        </ZoomButton>
      </ButtonGroup>
    </div>
  );
}

function ZoomButton({ label, onClick, disabled, active, children }: { label: string; onClick: () => void; disabled?: boolean; active?: boolean; children: React.ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button variant="outline" size="icon-sm" className={cn("size-7 text-muted-foreground", active && "text-accent-ui hover:text-accent-ui")} aria-pressed={active} aria-label={label} onClick={onClick} disabled={disabled}>
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent side="top">{label}</TooltipContent>
    </Tooltip>
  );
}
