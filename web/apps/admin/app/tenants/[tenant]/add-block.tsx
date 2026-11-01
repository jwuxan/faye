"use client";

import { Button } from "@faye/ui/components/button";
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@faye/ui/components/command";
import { Kbd } from "@faye/ui/components/kbd";
import { Popover, PopoverContent, PopoverTrigger } from "@faye/ui/components/popover";
import { Plus } from "lucide-react";
import { useEffect, useState } from "react";
import { PALETTE } from "../../../lib/flow";
import { BLOCK_ICONS } from "../../../lib/kinds";
import { Tile } from "../../ui";

/**
 * The block palette as a searchable list: Enter or a click adds the block where `where` says.
 * A block that cannot go there is shown disabled, with why (`problem`), so the palette never
 * offers what will not compile. "/" opens it from anywhere in the workspace outside a text field.
 */
export function AddBlock({
  onAdd,
  where,
  problem,
}: {
  onAdd: (name: string) => void;
  where: string;
  problem: (name: string) => string | null;
}) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "/" || e.metaKey || e.ctrlKey) return;
      if ((e.target as HTMLElement).closest("input, textarea, select, .cm-editor, [contenteditable]")) return;
      e.preventDefault();
      setOpen(true);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm" aria-label="Add block" title="Add block (/)" className="h-7 gap-1.5 px-2 text-xs">
          <Plus />
          <span className="hidden xl:inline">Add block</span>
          <Kbd className="ml-0.5 hidden h-4 min-w-4 text-xs xl:inline-flex">/</Kbd>
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-72 p-0">
        <Command>
          <CommandInput placeholder="Search blocks" className="text-sm" />
          <CommandList className="max-h-80">
            <CommandEmpty className="py-4 text-xs">No block matches</CommandEmpty>
            {(["Steps", "Logic"] as const).map((group) => (
              <CommandGroup key={group} heading={group}>
                {PALETTE.filter((b) => b.group === group).map((b) => {
                  const look = BLOCK_ICONS[b.name];
                  const why = open ? problem(b.name) : null;
                  return (
                    <CommandItem
                      key={b.name}
                      value={`${b.name} ${b.description}`}
                      disabled={why !== null}
                      onSelect={() => {
                        onAdd(b.name);
                        setOpen(false);
                      }}
                      className="items-start gap-2 py-1.5"
                    >
                      <Tile icon={look.icon} tone={look.tone} size="sm" />
                      <span className="grid min-w-0 gap-0.5">
                        <span className="text-sm">{b.name}</span>
                        <span className="text-xs leading-4 text-muted-foreground">{why ?? b.description}</span>
                      </span>
                    </CommandItem>
                  );
                })}
              </CommandGroup>
            ))}
          </CommandList>
          <div className="border-t px-3 py-2 text-xs text-muted-foreground">{where}</div>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
