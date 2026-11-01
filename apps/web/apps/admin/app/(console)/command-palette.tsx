"use client";

import { CommandDialog, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList, CommandShortcut } from "@faye/ui/components/command";
import { Kbd } from "@faye/ui/components/kbd";
import { useSidebar } from "@faye/ui/components/sidebar";
import { Building2, LayoutList, type LucideIcon, PanelLeft } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

/** Every keyboard shortcut the console has, for the sheet that holding ⌘ shows. */
const SHORTCUTS: { group: string; keys: string[]; does: string }[] = [
  { group: "Anywhere", keys: ["⌘K"], does: "Command palette" },
  { group: "Anywhere", keys: ["⌘B"], does: "Toggle sidebar" },
  { group: "Anywhere", keys: ["hold ⌘"], does: "Show shortcuts" },
  { group: "Login flow", keys: ["↑", "↓"], does: "Select block" },
  { group: "Login flow", keys: ["⌘↑", "⌘↓"], does: "Move block" },
  { group: "Login flow", keys: ["⌫"], does: "Delete block" },
  { group: "Login flow", keys: ["/"], does: "Add block" },
  { group: "Login flow", keys: ["⌘Z", "⇧⌘Z"], does: "Undo, redo" },
  { group: "Hooks", keys: ["⌘↵"], does: "Run test" },
];

/** How long ⌘ must be held, alone, before the shortcut sheet shows. */
const HOLD_MS = 500;

export type PaletteGroup = { label: string; items: { title: string; href: string; icon: LucideIcon }[] };

/** ⌘K: jump to any page of the tenant, switch tenants, or run a console command. */
export function CommandPalette({
  groups,
  tenants,
  open,
  setOpen,
}: {
  groups: PaletteGroup[];
  tenants: string[];
  open: boolean;
  setOpen: (open: boolean | ((open: boolean) => boolean)) => void;
}) {
  const router = useRouter();
  const { toggleSidebar } = useSidebar();

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() !== "k" || !(e.metaKey || e.ctrlKey)) return;
      e.preventDefault();
      setOpen((o) => !o);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [setOpen]);

  const run = (action: () => void) => {
    setOpen(false);
    action();
  };
  return (
    <CommandDialog open={open} onOpenChange={setOpen} title="Command palette" description="Go to a page or run a command" showCloseButton={false}>
      <CommandInput placeholder="Search or jump to…" />
      <CommandList>
        <CommandEmpty>No results</CommandEmpty>
        {groups.map((g) => (
          <CommandGroup key={g.label} heading={g.label}>
            {g.items.map((item) => (
              <CommandItem key={item.href} value={`${g.label} ${item.title}`} onSelect={() => run(() => router.push(item.href))}>
                <item.icon />
                {item.title}
              </CommandItem>
            ))}
          </CommandGroup>
        ))}
        <CommandGroup heading="Tenants">
          {tenants.map((t) => (
            <CommandItem key={t} value={`tenant ${t}`} onSelect={() => run(() => router.push(`/tenants/${encodeURIComponent(t)}/flow`))}>
              <Building2 />
              <span className="font-mono">{t}</span>
            </CommandItem>
          ))}
          <CommandItem value="all tenants" onSelect={() => run(() => router.push("/"))}>
            <LayoutList />
            All tenants
          </CommandItem>
        </CommandGroup>
        <CommandGroup heading="Console">
          <CommandItem value="toggle sidebar" onSelect={() => run(toggleSidebar)}>
            <PanelLeft />
            Toggle sidebar
            <CommandShortcut>⌘B</CommandShortcut>
          </CommandItem>
        </CommandGroup>
      </CommandList>
    </CommandDialog>
  );
}

/** Holding ⌘ (or Ctrl) on its own shows every shortcut; letting go, or pressing any other key, hides it. */
export function ShortcutSheet() {
  const [shown, setShown] = useState(false);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const hide = () => {
      clearTimeout(timer);
      setShown(false);
    };
    const onDown = (e: KeyboardEvent) => {
      if ((e.key === "Meta" || e.key === "Control") && !e.repeat) {
        clearTimeout(timer);
        timer = setTimeout(() => setShown(true), HOLD_MS);
      } else if (e.key !== "Meta" && e.key !== "Control") {
        hide();
      }
    };
    const onUp = (e: KeyboardEvent) => {
      if (e.key === "Meta" || e.key === "Control") hide();
    };
    window.addEventListener("keydown", onDown);
    window.addEventListener("keyup", onUp);
    window.addEventListener("blur", hide);
    return () => {
      hide();
      window.removeEventListener("keydown", onDown);
      window.removeEventListener("keyup", onUp);
      window.removeEventListener("blur", hide);
    };
  }, []);

  if (!shown) return null;
  const groups = [...new Set(SHORTCUTS.map((s) => s.group))];
  return (
    <div className="pointer-events-none fixed inset-0 z-50 grid animate-in fade-in-0 duration-150 place-items-center bg-background/40 backdrop-blur-xs" role="dialog" aria-label="Keyboard shortcuts">
      <div className="grid w-[min(--spacing(112),calc(100vw-(--spacing(8))))] animate-in fade-in-0 zoom-in-95 duration-150 gap-3 rounded-xl border bg-popover p-4 text-popover-foreground shadow-lg">
        {groups.map((g) => (
          <section key={g} className="grid gap-1.5">
            <p className="text-xs font-medium text-muted-foreground">{g}</p>
            <dl className="grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-1 text-xs">
              {SHORTCUTS.filter((s) => s.group === g).map((s) => (
                <div key={s.does} className="contents">
                  <dt>{s.does}</dt>
                  <dd className="flex justify-end gap-1">
                    {s.keys.map((k) => (
                      <Kbd key={k}>{k}</Kbd>
                    ))}
                  </dd>
                </div>
              ))}
            </dl>
          </section>
        ))}
      </div>
    </div>
  );
}
