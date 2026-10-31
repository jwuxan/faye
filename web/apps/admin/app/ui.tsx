"use client";

/** Small console primitives shared by the admin views. */
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

export function IconButton({ icon: Icon, label, className, ...props }: { icon: LucideIcon; label: string } & ComponentProps<"button">) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      className={cn(
        "grid size-7 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:pointer-events-none disabled:opacity-35",
        "focus-visible:outline-2 focus-visible:outline-ring",
        className,
      )}
      {...props}
    >
      <Icon className="size-4" strokeWidth={1.75} />
    </button>
  );
}

export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (on: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        "relative inline-flex h-[18px] w-[30px] shrink-0 items-center rounded-full transition-colors focus-visible:outline-2 focus-visible:outline-ring disabled:opacity-40",
        checked ? "bg-accent-ui" : "bg-input dark:bg-white/15",
      )}
    >
      <span className={cn("absolute size-[14px] rounded-full bg-white shadow-sm transition-transform", checked ? "translate-x-[14px]" : "translate-x-[2px]")} />
    </button>
  );
}

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
    <div role="radiogroup" aria-label={label} className="inline-flex rounded-md bg-muted p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={cn(
            "h-6 rounded-[5px] px-2 text-xs transition-colors",
            value === o.value ? "bg-background font-medium text-foreground shadow-xs dark:bg-white/10" : "text-muted-foreground hover:text-foreground",
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
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
    <span className={cn("inline-flex h-5 items-center gap-1 rounded-[4px] bg-muted px-1.5 text-[11px] text-muted-foreground", mono && "font-mono", className)}>
      {children}
    </span>
  );
}

export const field =
  "h-7 min-w-0 rounded-md border border-input bg-background px-2 text-[13px] shadow-xs outline-none transition-colors focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30 aria-invalid:border-danger dark:bg-white/5";

export function Select({ className, ...props }: ComponentProps<"select">) {
  return <select className={cn(field, "pr-6", className)} {...props} />;
}

export function Panel({ title, actions, children, className }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn("rounded-lg border bg-card", className)}>
      {(title || actions) && (
        <header className="flex h-10 items-center gap-2 border-b px-3">
          <h3 className="text-[13px] font-medium">{title}</h3>
          <div className="ml-auto flex items-center gap-1">{actions}</div>
        </header>
      )}
      {children}
    </section>
  );
}

export function Empty({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children?: ReactNode }) {
  return (
    <div className="grid place-items-center gap-2 py-16 text-center">
      <Icon className="size-5 text-muted-foreground" strokeWidth={1.5} />
      <p className="text-[13px] font-medium">{title}</p>
      {children && <div className="text-xs text-muted-foreground">{children}</div>}
    </div>
  );
}
