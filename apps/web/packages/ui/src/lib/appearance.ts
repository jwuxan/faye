"use client"

import { useSyncExternalStore } from "react"
import { APPEARANCE_KEY as KEY } from "./appearance-script"

/** What a person picked; "system" follows the OS. Kept per browser, per app (origin). */
export type Appearance = "light" | "dark" | "system"

function saved(): Appearance {
  try {
    const a = localStorage.getItem(KEY)
    return a === "light" || a === "dark" ? a : "system"
  } catch {
    // Private windows may refuse storage
    return "system"
  }
}

/** Saves the appearance and applies it at once. */
export function setAppearance(a: Appearance) {
  try {
    if (a === "system") localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, a)
  } catch {
    // Applies for this page only
  }
  const dark = a === "dark" || (a === "system" && matchMedia("(prefers-color-scheme: dark)").matches)
  document.documentElement.dataset.theme = dark ? "dark" : "light"
  document.documentElement.classList.toggle("dark", dark)
  // The theme may stay the same (system to light on a light OS), but the choice changed
  window.dispatchEvent(new Event(KEY))
}

// data-theme changes with the OS or another tab; the event, with a choice made on this page
function subscribe(onChange: () => void) {
  const observer = new MutationObserver(onChange)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] })
  window.addEventListener(KEY, onChange)
  return () => {
    observer.disconnect()
    window.removeEventListener(KEY, onChange)
  }
}

/** The saved appearance and the theme it resolves to; "system" and "light" on the server. */
export function useAppearance(): { appearance: Appearance; theme: "light" | "dark" } {
  const appearance = useSyncExternalStore(subscribe, saved, () => "system" as const)
  const theme = useSyncExternalStore(
    subscribe,
    () => (document.documentElement.dataset.theme === "dark" ? "dark" : "light"),
    () => "light" as const,
  )
  return { appearance, theme }
}
