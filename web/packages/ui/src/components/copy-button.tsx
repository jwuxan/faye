"use client"

import * as React from "react"
import { CheckIcon, CopyIcon } from "lucide-react"
import { cn } from "../lib/utils"
import { useCopy } from "../hooks/use-copy"
import { Button } from "./button"

/** Copies `value`; the icon turns into a checkmark for a moment, then back. */
function CopyButton({
  value,
  label = "Copy",
  className,
  ...props
}: Omit<React.ComponentProps<typeof Button>, "value" | "onClick"> & {
  value: string
  label?: string
}) {
  const { copied, copy } = useCopy()
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      data-slot="copy-button"
      data-copied={copied || undefined}
      aria-label={copied ? "Copied" : label}
      title={copied ? "Copied" : label}
      className={cn("relative text-muted-foreground hover:text-foreground", className)}
      onClick={() => void copy(value)}
      {...props}
    >
      <CopyIcon
        className={cn("transition-all duration-200", copied && "scale-50 opacity-0")}
        strokeWidth={1.75}
      />
      <CheckIcon
        className={cn(
          "absolute text-success transition-all duration-200",
          !copied && "scale-50 opacity-0"
        )}
        strokeWidth={2}
        aria-hidden
      />
      <span className="sr-only" aria-live="polite">
        {copied ? "Copied" : ""}
      </span>
    </Button>
  )
}

export { CopyButton }
