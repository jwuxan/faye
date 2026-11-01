import * as React from "react"

/** How long a copy button shows its checkmark before it goes back to the copy icon. */
const COPIED_MS = 1500

/** Copies text to the clipboard; `copied` is true for a moment after a copy that worked. */
export function useCopy() {
  const [copied, setCopied] = React.useState(false)
  const timer = React.useRef<ReturnType<typeof setTimeout>>(undefined)

  React.useEffect(() => () => clearTimeout(timer.current), [])

  const copy = React.useCallback(async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      return
    }
    setCopied(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), COPIED_MS)
  }, [])

  return { copied, copy }
}
