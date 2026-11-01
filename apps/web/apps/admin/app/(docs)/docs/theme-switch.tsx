"use client";

import { type Appearance, setAppearance, useAppearance } from "@faye/ui/lib/appearance";
import { cn } from "@faye/ui/lib/utils";
import { Airplay, type LucideIcon, Moon, Sun } from "lucide-react";
import { type ComponentProps, useSyncExternalStore } from "react";

const CHOICES: [Appearance, LucideIcon, string][] = [
  ["light", Sun, "Light"],
  ["dark", Moon, "Dark"],
  ["system", Airplay, "System"],
];

const noop = () => () => {};

/**
 * The docs' light, dark or system switch, drawn like Fumadocs' own. It sets the console's
 * appearance (the same localStorage key), so the console and the docs always match.
 */
export function ThemeSwitch({ className, ...props }: ComponentProps<"div">) {
  const { appearance } = useAppearance();
  const mounted = useSyncExternalStore(noop, () => true, () => false);
  return (
    <div className={cn("inline-flex items-center overflow-hidden rounded-full border p-1 *:rounded-full", className)} {...props}>
      {CHOICES.map(([value, Icon, label]) => (
        <button
          key={value}
          type="button"
          aria-label={label}
          aria-pressed={mounted && appearance === value}
          onClick={() => setAppearance(value)}
          className={cn("size-6.5 p-1.5", mounted && appearance === value ? "bg-fd-accent text-fd-accent-foreground" : "text-fd-muted-foreground")}
        >
          <Icon className="size-full" />
        </button>
      ))}
    </div>
  );
}
