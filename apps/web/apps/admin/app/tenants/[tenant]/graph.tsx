"use client";

import { ButtonGroup } from "@faye/ui/components/button-group";
import { Button } from "@faye/ui/components/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@faye/ui/components/tooltip";
import { cn } from "@faye/ui/lib/utils";
import { CircleCheck, Expand, type LucideIcon, Minus, Plus, Scan } from "lucide-react";
import { type PointerEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { CONSENT, ENTRIES, SECTIONS } from "../../../lib/entries";
import { addressKey, consentStart, type Entry, type FlowDoc, type ListKind, type Section } from "../../../lib/flow";
import { kindOf, type Tone } from "../../../lib/kinds";
import type { Findings } from "./tree";

type GNode = {
  key: string;
  label: string;
  detail: string;
  icon?: LucideIcon;
  tone?: Tone;
  x: number;
  y: number;
  option: boolean;
  parent?: string;
  kind: "entry" | "section" | "end";
  /** What the block says, without its nested lists: a change here flashes the node. */
  sign: string;
};

const W = 200;
const H = 40;
const GAP_X = 32;
const GAP_Y = 12;
const PAD = 24;
const MIN_ZOOM = 0.25;
const MAX_ZOOM = 2;
const ZOOMS = [0.25, 0.33, 0.5, 0.67, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 2];

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
        detail: e.body.block ?? look.label,
        icon: look.icon,
        tone: look.tone,
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
    const list = doc[section] ?? [];
    // A section the flow does not have is not drawn (Approve, for a flow without QR code sign in)
    if (list.length === 0 && section !== SECTIONS[0]) continue;
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
  reserve = 0,
  tenant,
  readOnly = false,
  fit = "width",
}: {
  doc: FlowDoc;
  visited: Set<string> | null;
  visitKeys: Map<string, string>;
  selected: string | null;
  onSelect?: (key: string) => void;
  findings: Findings;
  /** Pixels on the right that something floats over: the fit leaves them out and the graph can scroll clear of them. */
  reserve?: number;
  /** The tenant whose /view page the Full screen control opens; unset or read only: no control. */
  tenant?: string;
  /** Display only: blocks cannot be selected. */
  readOnly?: boolean;
  /** What Fit does: the width, as in the workspace, or the whole graph inside the scroller. */
  fit?: "width" | "contain";
}) {
  const { nodes, width, height } = useMemo(() => layout(doc), [doc]);
  const byKey = useMemo(() => new Map(nodes.map((n) => [n.key, n])), [nodes]);
  const canvas = useRef<HTMLDivElement>(null);

  // The viewport: translate (x, y) then scale k, applied to a wrapper; vpRef mirrors it so rapid
  // wheel and pointer events compound on the latest values, not on a rendered frame
  const [vp, setVp] = useState({ x: 0, y: 0, k: 1 });
  const vpRef = useRef(vp);
  const move = useCallback((next: Partial<{ x: number; y: number; k: number }>) => {
    const v = { ...vpRef.current, ...next };
    vpRef.current = v;
    setVp(v);
  }, []);
  const [auto, setAuto] = useState(true);

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

  // Text widths: end pills and section detail offsets are sized from the rendered text. The mono
  // webfont lands late, so measure again once fonts are ready (the fallback is a per-char guess).
  const [metrics, setMetrics] = useState<{ title: Map<string, number>; pill: Map<string, number> }>({ title: new Map(), pill: new Map() });
  useEffect(() => {
    const ctx = document.createElement("canvas").getContext("2d");
    if (!ctx) return;
    let live = true;
    const measure = () => {
      const cs = getComputedStyle(document.body);
      const title = new Map<string, number>();
      const pill = new Map<string, number>();
      ctx.font = `600 14px ${cs.fontFamily}`;
      for (const n of nodes) if (n.kind === "section") title.set(n.label, ctx.measureText(n.label).width);
      ctx.font = `12px ${cs.fontFamily}`;
      for (const n of nodes) if (n.kind === "end") pill.set(n.label, ctx.measureText(n.label).width);
      if (live) setMetrics({ title, pill });
    };
    measure();
    void document.fonts.ready.then(measure);
    return () => {
      live = false;
    };
  }, [nodes]);

  // Fit: "width" scales the whole graph into the visible width (left of whatever the `reserve`d
  // right edge holds back), down to the minimum zoom, anchored top-left under the chip's
  // clearance; "contain" centres the whole graph in the area below the chip. Keep refitting on
  // resize until the author pans or zooms by hand.
  useEffect(() => {
    const box = canvas.current;
    if (!box || !auto) return;
    const apply = () => {
      const w = width + PAD * 2;
      const h = height + PAD * 2;
      if (fit === "width") {
        const k = Math.min(1, Math.max(MIN_ZOOM, Math.floor(((box.clientWidth - reserve - 8) / w) * 100) / 100));
        move({ k, x: 16, y: 56 });
      } else {
        const rw = box.clientWidth - reserve;
        const rh = box.clientHeight - 56;
        const k = Math.min(1.5, Math.max(MIN_ZOOM, Math.floor(Math.min(rw / w, rh / h) * 100) / 100));
        move({ k, x: (rw - w * k) / 2, y: 56 + (rh - h * k) / 2 });
      }
    };
    apply();
    const observer = new ResizeObserver(apply);
    observer.observe(box);
    return () => observer.disconnect();
  }, [auto, width, height, reserve, fit, move]);

  const zoomTo = useCallback(
    (next: number, mx: number, my: number) => {
      const k0 = vpRef.current.k;
      const k1 = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next));
      if (k1 === k0) return;
      setAuto(false);
      const v = vpRef.current;
      move({ k: k1, x: mx - (mx - v.x) * (k1 / k0), y: my - (my - v.y) * (k1 / k0) });
    },
    [move],
  );

  // Wheel always stops the page reacting: pinch and Ctrl/⌘ + wheel zoom toward the pointer, a
  // two-finger scroll or a mouse wheel pans (shift turns a vertical wheel into a horizontal pan).
  // React's onWheel is passive, so this needs a native listener to preventDefault.
  useEffect(() => {
    const box = canvas.current;
    if (!box) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const unit = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 400 : 1;
      const dx = e.deltaX * unit;
      const dy = e.deltaY * unit;
      if (e.ctrlKey || e.metaKey) {
        const rect = box.getBoundingClientRect();
        const factor = 2 ** (-Math.min(25, Math.max(-25, dy)) * 0.02);
        zoomTo(vpRef.current.k * factor, e.clientX - rect.left, e.clientY - rect.top);
      } else {
        setAuto(false);
        move(e.shiftKey && dx === 0 ? { x: vpRef.current.x - dy } : { x: vpRef.current.x - dx, y: vpRef.current.y - dy });
      }
    };
    box.addEventListener("wheel", onWheel, { passive: false });
    return () => box.removeEventListener("wheel", onWheel);
  }, [zoomTo, move]);

  // Drag to pan, in any direction without bounds: a press starts a maybe-pan; past 4px it captures
  // the pointer (capturing on down would retarget the click and break node selection), and the
  // click it ends in is swallowed so a drag that starts on a node does not select it
  const [panning, setPanning] = useState(false);
  const pan = useRef<{ id: number; x: number; y: number; vx: number; vy: number; active: boolean } | null>(null);
  const swallowClick = useRef(false);
  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    swallowClick.current = false; // a press after the pan-ending click was swallowed (or missed)
    if (pan.current || (e.pointerType === "mouse" && e.button !== 0 && e.button !== 1)) return;
    if (e.button === 1) e.preventDefault();
    pan.current = { id: e.pointerId, x: e.clientX, y: e.clientY, vx: vpRef.current.x, vy: vpRef.current.y, active: false };
  };
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const p = pan.current;
    if (!p || p.id !== e.pointerId) return;
    const dx = e.clientX - p.x;
    const dy = e.clientY - p.y;
    if (!p.active) {
      if (Math.hypot(dx, dy) <= 4) return;
      p.active = true;
      e.currentTarget.setPointerCapture(e.pointerId);
      setAuto(false);
      setPanning(true);
    }
    move({ x: p.vx + dx, y: p.vy + dy });
  };
  const onPointerUp = (e: PointerEvent<HTMLDivElement>) => {
    const p = pan.current;
    if (!p || p.id !== e.pointerId) return;
    pan.current = null;
    if (p.active) {
      swallowClick.current = true;
      setPanning(false);
    }
  };

  // Bring a selected block into view with a small margin, animated unless reduced motion is on
  const [animating, setAnimating] = useState(false);
  useEffect(() => {
    if (!selected) return;
    const box = canvas.current;
    const n = byKey.get(selected);
    if (!box || !n) return;
    const v = vpRef.current;
    const l = v.x + (n.x + PAD) * v.k;
    const t = v.y + (n.y + PAD) * v.k;
    const r = l + W * v.k;
    const b = t + H * v.k;
    const vw = box.clientWidth - reserve;
    const vh = box.clientHeight;
    if (l >= 24 && t >= 24 && r <= vw - 24 && b <= vh - 24) return;
    let x = v.x;
    let y = v.y;
    if (l < 24) x += 24 - l;
    else if (r > vw - 24) x -= r - vw + 24;
    if (t < 24) y += 24 - t;
    else if (b > vh - 24) y -= b - vh + 24;
    if (x === v.x && y === v.y) return;
    const animate = !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    // Next frame, so the transition and the move land in one render
    const frame = requestAnimationFrame(() => {
      if (animate) {
        setAnimating(true);
        setTimeout(() => setAnimating(false), 240);
      }
      move({ x, y });
    });
    return () => cancelAnimationFrame(frame);
  }, [selected, byKey, reserve, move]);

  const step = (dir: 1 | -1) => {
    const target = dir > 0 ? ZOOMS.find((z) => z > vpRef.current.k + 0.001) : [...ZOOMS].reverse().find((z) => z < vpRef.current.k - 0.001);
    const box = canvas.current;
    if (target !== undefined && box) zoomTo(target, box.clientWidth / 2, box.clientHeight / 2);
  };

  const on = (n: GNode) => !visited || n.kind !== "entry" || visited.has(visitKeys.get(n.key) ?? "");
  const lit = (n: GNode) => !!visited && n.kind === "entry" && on(n);

  return (
    <div className="relative h-full min-h-0">
      <div
        ref={canvas}
        // The dot grid moves and scales with the viewport, so it reads as the canvas's own surface
        style={{ backgroundSize: `${16 * vp.k}px ${16 * vp.k}px`, backgroundPosition: `${vp.x}px ${vp.y}px` }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onClickCapture={(e) => {
          // The click that ends a pan must not select whatever it started on
          if (swallowClick.current) {
            swallowClick.current = false;
            e.stopPropagation();
            e.preventDefault();
          }
        }}
        className={cn(
          "h-full cursor-grab touch-none overflow-hidden bg-surface-2 bg-[radial-gradient(var(--border)_--spacing(0.25),transparent_--spacing(0.25))]",
          panning && "cursor-grabbing select-none [&_*]:cursor-grabbing!",
        )}
      >
        <div
          className={cn("w-fit", animating && "transition-transform duration-200 ease-out")}
          style={{ transform: `translate(${vp.x}px, ${vp.y}px) scale(${vp.k})`, transformOrigin: "0 0", willChange: "transform" }}
        >
        <svg
          width={width + PAD * 2}
          height={height + PAD * 2}
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
                stroke={active ? "var(--accent-ui)" : "var(--edge)"}
                strokeWidth={active ? 2 : 1.25}
                className="transition-[stroke,stroke-width] duration-300"
              />
            );
          })}
          {nodes.map((n) => {
            if (n.kind === "section") {
              return (
                <g key={n.key} transform={`translate(${n.x},${n.y})`} className="transition-transform duration-300 ease-out motion-reduce:transition-none">
                  <text x={0} y={24} className="text-sm" fontWeight={600} fill="var(--foreground)">
                    {n.label}
                  </text>
                  <text x={(metrics.title.get(n.label) ?? n.label.length * 7.6) + 8} y={24} className="text-xs" fontFamily="var(--font-mono)" fill="var(--muted-foreground)">
                    {n.detail}
                  </text>
                </g>
              );
            }
            if (n.kind === "end") {
              return (
                <g key={n.key} transform={`translate(${n.x},${n.y + 8})`} opacity={visited ? 0.5 : 1} className="transition-[transform,opacity] duration-300 ease-out motion-reduce:transition-[opacity]">
                  <rect width={27 + (metrics.pill.get(n.label) ?? n.label.length * 7.4) + 10} height={24} rx={12} fill="var(--card)" stroke="var(--border)" />
                  <CircleCheck x={8} y={5} width={14} height={14} strokeWidth={1.75} color="var(--success)" />
                  <text x={27} y={16} className="text-xs" fill="var(--muted-foreground)">
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
                transform={`translate(${n.x},${n.y})`}
                opacity={on(n) ? 1 : 0.35}
                className={cn(
                  "outline-none transition-[transform,opacity] duration-300 ease-out motion-reduce:transition-[opacity]",
                  readOnly ? "cursor-grab" : "cursor-pointer [&:focus-visible>rect:first-of-type]:stroke-[var(--ring)]",
                )}
                aria-label={`${n.label}: ${n.detail}`}
                {...(readOnly
                  ? {}
                  : { role: "button", tabIndex: -1, "aria-pressed": isSelected, onClick: () => onSelect?.(n.key) })}
              >
                <title>{`${n.label}: ${n.detail}`}</title>
                <rect
                  width={W}
                  height={H}
                  rx={10}
                  fill={isSelected ? "color-mix(in oklch, var(--accent-ui) 8%, var(--card))" : "var(--card)"}
                  stroke={accent ? "var(--accent-ui)" : "var(--border)"}
                  strokeWidth={isSelected ? 2 : accent ? 1.5 : 1}
                  className="transition-[stroke,fill] duration-200"
                />
                {flash && <rect width={W} height={H} rx={10} fill="none" stroke="var(--accent-ui)" strokeWidth={2} className="graph-flash" />}
                <rect x={8} y={8} width={24} height={24} rx={7} fill={n.tone ? `var(--tone-${n.tone})` : "var(--muted)"} />
                {n.icon && <n.icon x={13} y={13} width={14} height={14} strokeWidth={2.1} color={n.tone ? "var(--on-fill)" : "var(--muted-foreground)"} />}
                <text x={40} y={18} className="text-xs" fontWeight={500} fontFamily="var(--font-mono)" fill="var(--foreground)">
                  {n.label.length > 19 ? `${n.label.slice(0, 18)}…` : n.label}
                </text>
                <text x={40} y={32} className="text-xs" fontFamily={/^[A-Z]/.test(n.detail) ? undefined : "var(--font-mono)"} fill="var(--muted-foreground)">
                  {n.detail.length > 21 ? `${n.detail.slice(0, 20)}…` : n.detail}
                </text>
                {found && (found.errors.length > 0 || found.warnings.length > 0) && (
                  <circle cx={W - 10} cy={10} r={3.5} fill={found.errors.length > 0 ? "var(--danger)" : "var(--warning)"} />
                )}
                {n.option && <circle cx={0} cy={H / 2} r={3} fill="var(--card)" stroke={accent ? "var(--accent-ui)" : "var(--edge)"} />}
              </g>
            );
          })}
        </svg>
        </div>
      </div>
      <ButtonGroup className="absolute bottom-3 left-3 rounded-md bg-background shadow-sm" aria-label="Zoom">
        <ZoomButton label="Zoom out" onClick={() => step(-1)} disabled={vp.k <= ZOOMS[0]}>
          <Minus />
        </ZoomButton>
        <Button variant="outline" size="sm" className="h-7 w-12 px-0 text-xs tabular-nums" onClick={() => {
            const box = canvas.current;
            if (box) zoomTo(1, box.clientWidth / 2, box.clientHeight / 2);
          }} aria-label="Reset zoom">
          {Math.round(vp.k * 100)}%
        </Button>
        <ZoomButton label="Zoom in" onClick={() => step(1)} disabled={vp.k >= ZOOMS[ZOOMS.length - 1]}>
          <Plus />
        </ZoomButton>
        <ZoomButton label={fit === "width" ? "Fit width" : "Fit"} onClick={() => setAuto(true)} active={auto}>
          <Scan />
        </ZoomButton>
        {tenant && !readOnly && (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button asChild variant="outline" size="icon-sm" className="size-7 text-muted-foreground" aria-label="Open graph full screen in a new tab">
                <a href={`/view/${encodeURIComponent(tenant)}`} target="_blank" rel="noopener">
                  <Expand />
                </a>
              </Button>
            </TooltipTrigger>
            <TooltipContent side="top">Full screen (new tab)</TooltipContent>
          </Tooltip>
        )}
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
