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
 * "/" opens it from anywhere in the workspace outside a text field.
 */
export function AddBlock({ onAdd, where }: { onAdd: (name: string) => void; where: string }) {
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
        <Button variant="outline" size="sm" className="h-7 gap-1.5 px-2 text-xs">
          <Plus />
          Add block
          <Kbd className="ml-0.5 h-4 min-w-4 text-[10px]">/</Kbd>
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-72 p-0">
        <Command>
          <CommandInput placeholder="Search blocks" className="text-[13px]" />
          <CommandList className="max-h-80">
            <CommandEmpty className="py-4 text-xs">No block matches</CommandEmpty>
            {(["Steps", "Logic"] as const).map((group) => (
              <CommandGroup key={group} heading={group}>
                {PALETTE.filter((b) => b.group === group).map((b) => {
                  const look = BLOCK_ICONS[b.name];
                  return (
                    <CommandItem
                      key={b.name}
                      value={`${b.name} ${b.description}`}
                      onSelect={() => {
                        onAdd(b.name);
                        setOpen(false);
                      }}
                      className="items-start gap-2 py-1.5"
                    >
                      <Tile icon={look.icon} category={look.category} size="sm" />
                      <span className="grid min-w-0 gap-0.5">
                        <span className="text-[13px]">{b.name}</span>
                        <span className="text-[11px] leading-4 text-muted-foreground">{b.description}</span>
                      </span>
                    </CommandItem>
                  );
                })}
              </CommandGroup>
            ))}
          </CommandList>
          <div className="border-t px-3 py-2 text-[11px] text-muted-foreground">{where}</div>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
