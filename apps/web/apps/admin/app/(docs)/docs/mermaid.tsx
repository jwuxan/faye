"use client";

import { useAppearance } from "@faye/ui/lib/appearance";
import { useEffect, useId, useState } from "react";

/**
 * A ```mermaid block of a doc, drawn in the browser in the reader's theme. Until it draws, and
 * if the chart does not parse, the reader sees its source, which is readable text too.
 */
export function Mermaid({ chart }: { chart: string }) {
  const id = useId().replace(/[^a-zA-Z0-9]/g, "");
  const { theme } = useAppearance();
  const [svg, setSvg] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const { default: mermaid } = await import("mermaid");
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: "strict",
        fontFamily: "inherit",
        theme: theme === "dark" ? "dark" : "neutral",
      });
      try {
        const { svg } = await mermaid.render(`mermaid-${id}`, chart);
        if (!cancelled) setSvg(svg);
      } catch {
        if (!cancelled) setSvg(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [chart, id, theme]);

  if (svg === null) {
    return (
      <pre className="docs-mermaid-source" aria-label="Diagram source">
        <code>{chart}</code>
      </pre>
    );
  }
  // Mermaid's own output at securityLevel "strict": sanitized SVG drawn from the doc's chart text
  return <div className="docs-mermaid" role="img" aria-label="Diagram" dangerouslySetInnerHTML={{ __html: svg }} />;
}
