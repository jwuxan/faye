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
import type { LucideIcon } from "lucide-react";
import type { ComponentProps, ReactNode } from "react";
import { type Category, TILE } from "../lib/kinds";

export function Tile({ icon: Icon, category, size = "md" }: { icon: LucideIcon; category: Category; size?: "sm" | "md" }) {
  return (
    <span className={cn("grid shrink-0 place-items-center rounded-[4px]", TILE[category], size === "md" ? "size-6" : "size-5")}>
      <Icon className={size === "md" ? "size-4" : "size-3.5"} strokeWidth={1.75} />
    </span>
  );
}

/** A ghost icon button with its label as a tooltip. */
export function IconButton({ icon: Icon, label, className, ...props }: { icon: LucideIcon; label: string } & ComponentProps<typeof Button>) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button type="button" variant="ghost" size="icon-sm" aria-label={label} className={cn("size-7 text-muted-foreground hover:text-foreground", className)} {...props}>
          <Icon strokeWidth={1.75} />
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
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
  options: { value: T; label: ReactNode }[];
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
      className="h-7 [&>*]:h-7 [&>*]:px-2 [&>*]:text-xs"
    >
      {options.map((o) => (
        <ToggleGroupItem key={o.value} value={o.value} className="data-[state=on]:font-medium">
          {o.label}
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
          <SelectItem value={NONE} className="text-xs text-muted-foreground">
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
  return (
    <div className={cn("grid grid-cols-[88px_1fr] gap-3", align === "center" ? "items-center" : "items-start")}>
      <span className={cn("text-xs text-muted-foreground", align === "start" && "pt-1.5")}>{label}</span>
      <div className="flex min-w-0 flex-wrap items-center gap-2">{children}</div>
    </div>
  );
}

export function Chip({ children, mono, className }: { children: ReactNode; mono?: boolean; className?: string }) {
  return (
    <Badge variant="secondary" className={cn("h-5 rounded-[4px] px-1.5 text-[11px] font-normal text-muted-foreground", mono && "font-mono", className)}>
      {children}
    </Badge>
  );
}

/** Input sizing for the dense property panels; use with the shared Input. */
export const dense = "h-7 px-2 text-[13px] md:text-[13px]";
/** Dense and monospaced, for ids, paths and values. */
export const denseMono = "h-7 px-2 font-mono text-xs md:text-xs";

/** A section heading inside a panel. */
export function Overline({ children, className }: { children: ReactNode; className?: string }) {
  return <h4 className={cn("text-[11px] font-medium tracking-[0.04em] text-muted-foreground uppercase", className)}>{children}</h4>;
}

export function Empty({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children?: ReactNode }) {
  return (
    <EmptyRoot className="h-full border-0">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <Icon />
        </EmptyMedia>
        <EmptyTitle className="text-[14px]">{title}</EmptyTitle>
      </EmptyHeader>
      {children && (
        <EmptyContent className="text-xs text-muted-foreground">{children}</EmptyContent>
      )}
    </EmptyRoot>
  );
}
