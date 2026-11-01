"use client";

/** Console conveniences over the shared shadcn components: same components, the props the views use. */
import { Badge } from "@faye/ui/components/badge";
import { Button } from "@faye/ui/components/button";
import { Empty as EmptyRoot, EmptyContent, EmptyHeader, EmptyMedia, EmptyTitle } from "@faye/ui/components/empty";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@faye/ui/components/select";
import { Switch as SwitchRoot } from "@faye/ui/components/switch";
import { ToggleGroup, ToggleGroupItem } from "@faye/ui/components/toggle-group";
import { Tooltip, TooltipContent, TooltipTrigger } from "@faye/ui/components/tooltip";
import { cn } from "@faye/ui/lib/utils";
import { useCopy } from "@faye/ui/hooks/use-copy";
import { Check, CircleHelp, Copy, type LucideIcon } from "lucide-react";
import type { ComponentProps, ReactNode } from "react";
import { TILE, type Tone } from "../../lib/kinds";

const TILE_SIZE = { sm: "size-5 rounded-sm", md: "size-6 rounded-md", lg: "size-7 rounded-md" } as const;

/**
 * An icon (a block's, an application's) or a letter (a tenant's) on a filled tile in its hue. A muted
 * tile (a block that cannot be added) drops the hue for an outlined neutral one: a faded hue under a
 * white glyph reads as a smudge, not as unavailable.
 */
export function Tile({
  icon: Icon,
  letter,
  tone,
  size = "md",
  muted = false,
}: { tone: Tone; size?: keyof typeof TILE_SIZE; muted?: boolean } & ({ icon: LucideIcon; letter?: never } | { letter: string; icon?: never })) {
  return (
    <span
      className={cn(
        "grid shrink-0 place-items-center font-sans text-xs font-semibold uppercase",
        muted ? "border border-dashed border-border bg-muted text-muted-foreground" : cn("text-on-fill shadow-tile", TILE[tone]),
        TILE_SIZE[size],
      )}
    >
      {/* text-current: menus color bare icons muted, which would gray the glyph on its hue */}
      {Icon ? <Icon className={cn("text-current", size === "sm" ? "size-3" : "size-3.5")} strokeWidth={2.1} /> : letter.charAt(0)}
    </span>
  );
}

/** A ghost icon button with its label as a tooltip. */
export function IconButton({ icon: Icon, label, className, iconClassName, ...props }: { icon: LucideIcon; label: string; iconClassName?: string } & ComponentProps<typeof Button>) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button type="button" variant="ghost" size="icon-sm" aria-label={label} className={cn("size-7 text-muted-foreground hover:text-foreground", className)} {...props}>
          <Icon strokeWidth={1.75} className={iconClassName} />
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}

/** An icon button that copies `value`, showing a checkmark for a moment once it has. */
export function CopyButton({ value, label = "Copy", className }: { value: string; label?: string; className?: string }) {
  const { copied, copy } = useCopy();
  return (
    <IconButton icon={copied ? Check : Copy} label={copied ? "Copied" : label} onClick={() => void copy(value)}
      iconClassName={copied ? "pop-in" : undefined}
      className={cn(className, copied && "text-success hover:text-success")} />
  );
}

/** A (?) beside a heading or control that explains it in a tooltip; focusable, so keyboard users get it too. */
export function Help({ children, side = "top" }: { children: ReactNode; side?: "top" | "right" | "bottom" | "left" }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button type="button" variant="ghost" size="icon-xs" aria-label="Help" className="size-4 rounded-full text-muted-foreground/70 hover:bg-transparent hover:text-foreground focus-visible:text-foreground">
          <CircleHelp className="size-3.5" strokeWidth={1.75} />
        </Button>
      </TooltipTrigger>
      <TooltipContent side={side} className="max-w-64 text-xs leading-snug text-pretty">
        {children}
      </TooltipContent>
    </Tooltip>
  );
}

export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (on: boolean) => void; label: string; disabled?: boolean }) {
  return <SwitchRoot checked={checked} onCheckedChange={onChange} aria-label={label} disabled={disabled} />;
}

/** One of a few values, as a segmented control. */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T;
  options: { value: T; label: ReactNode; disabled?: boolean }[];
  onChange: (v: T) => void;
  label: string;
}) {
  return (
    <ToggleGroup
      type="single"
      variant="outline"
      size="sm"
      aria-label={label}
      value={value}
      onValueChange={(v) => v && onChange(v as T)}
      className="h-7 max-w-full [&>*]:h-7 [&>*]:px-2 [&>*]:text-xs"
    >
      {/* Items shrink rather than spill out of a narrow panel */}
      {options.map((o) => (
        <ToggleGroupItem key={o.value} value={o.value} disabled={o.disabled} title={typeof o.label === "string" ? o.label : undefined} className="min-w-0 shrink data-[state=on]:font-medium">
          <span className="inline-flex min-w-0 items-center gap-1.5">{o.label}</span>
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  );
}

/** Radix Select needs a non-empty value; this stands for "none". */
const NONE = "__none__";

/** A select over string options; "" is the empty choice, labelled by `none` when given. */
export function Choice({
  value,
  options,
  onChange,
  label,
  none,
  className,
  mono = true,
  disabled,
}: {
  value: string;
  options: { value: string; label?: ReactNode }[];
  onChange: (v: string) => void;
  label: string;
  none?: string;
  className?: string;
  mono?: boolean;
  disabled?: boolean;
}) {
  return (
    <Select value={value === "" ? NONE : value} onValueChange={(v) => onChange(v === NONE ? "" : v)} disabled={disabled}>
      <SelectTrigger size="sm" aria-label={label} className={cn("h-7 min-w-0 px-2 text-xs data-[size=sm]:h-7", mono && "font-mono", className)}>
        <SelectValue placeholder={none ?? "choose"} />
      </SelectTrigger>
      <SelectContent>
        {none !== undefined && (
          <SelectItem value={NONE} className={cn("text-xs text-muted-foreground", mono && "font-mono")}>
            {none}
          </SelectItem>
        )}
        {options.map((o) => (
          <SelectItem key={o.value || NONE} value={o.value || NONE} className={cn("text-xs", mono && "font-mono")}>
            {o.label ?? o.value}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** A label column and a control column, like a property panel. */
export function Prop({ label, children, align = "center" }: { label: string; children: ReactNode; align?: "center" | "start" }) {
  // In a narrow panel the label goes above its control, which then has the full width
  return (
    <div className="@container">
      <div className={cn("grid gap-1.5 @xs:grid-cols-[--spacing(22)_1fr] @xs:gap-3", align === "center" ? "@xs:items-center" : "items-start")}>
        <span className={cn("text-xs text-muted-foreground", align === "start" && "@xs:pt-1.5")}>{label}</span>
        <div className="flex min-w-0 flex-wrap items-center gap-2">{children}</div>
      </div>
    </div>
  );
}

export function Chip({ children, mono, className }: { children: ReactNode; mono?: boolean; className?: string }) {
  return (
    <Badge variant="secondary" className={cn("h-5 rounded-sm px-1.5 text-xs font-normal text-secondary-foreground/80", mono && "font-mono", className)}>
      {children}
    </Badge>
  );
}

/** Input sizing for the dense property panels; use with the shared Input. */
export const dense = "h-7 px-2 text-sm";
/** Dense and monospaced, for ids, paths and values. */
export const denseMono = "h-7 px-2 font-mono text-xs md:text-xs";

/** A section heading inside a panel. Not a document heading: panels use these as visual group labels only. */
export function Overline({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cn("text-xs font-medium text-muted-foreground", className)}>{children}</p>;
}

export function Empty({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children?: ReactNode }) {
  return (
    <EmptyRoot className="h-full border-0">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <Icon />
        </EmptyMedia>
        <EmptyTitle className="text-base">{title}</EmptyTitle>
      </EmptyHeader>
      {children && (
        <EmptyContent className="text-xs text-muted-foreground">{children}</EmptyContent>
      )}
    </EmptyRoot>
  );
}

/** The bar at the top of a pane: its title, tabs or tools on one line, controls at the right. */
export function PaneBar({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn("flex h-10 shrink-0 items-center gap-2 border-b px-4", className)}>{children}</div>;
}

/**
 * The bar under the flow workspace's tabs, the same height and padding on every tab: what the tab
 * shows or its state on the left, the tab's actions on the right.
 */
export function TabToolbar({ children, actions }: { children?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex h-10 shrink-0 items-center gap-2 border-b px-4">
      <div className="flex min-w-0 flex-1 items-center gap-2 overflow-hidden text-xs whitespace-nowrap text-muted-foreground *:[span]:truncate">{children}</div>
      {actions && <div className="flex shrink-0 items-center gap-1">{actions}</div>}
    </div>
  );
}

/**
 * A titled group of a tab panel (Settings, Preview): heading row with optional help and action, an
 * optional one line description, then the rows. Groups are split by a rule, with the same padding.
 */
export function PanelSection({
  title,
  help,
  action,
  description,
  contentClassName,
  children,
}: {
  title: string;
  help?: ReactNode;
  action?: ReactNode;
  description?: ReactNode;
  /** Caps or styles the section's whole content, header row included. */
  contentClassName?: string;
  children: ReactNode;
}) {
  return (
    <section aria-label={title} className="border-b px-4 pt-4 pb-5 last:border-b-0">
      <div className={cn("grid gap-3", contentClassName)}>
        <div className="grid gap-1">
          <div className="flex h-5 items-center gap-1">
            <h3 className="text-xs font-semibold text-foreground">{title}</h3>
            {help && <Help>{help}</Help>}
            {action && <div className="ml-auto flex items-center">{action}</div>}
          </div>
          {description && <p className="text-xs text-pretty text-muted-foreground">{description}</p>}
        </div>
        {children}
      </div>
    </section>
  );
}
