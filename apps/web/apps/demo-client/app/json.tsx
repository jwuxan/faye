import type { ReactNode } from "react";

/** A string (and the colon after it, if it is a key), a literal, or a number, in JSON.stringify output. */
const TOKEN = /("(?:\\.|[^"\\])*")(\s*:)?|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g;

/** A value as indented JSON, its keys, strings and other literals colored from the theme. */
export function Json({ value, className, id }: { value: unknown; className?: string; id?: string }) {
  const text = JSON.stringify(value, null, 2);
  const parts: ReactNode[] = [];
  let at = 0;
  for (const m of text.matchAll(TOKEN)) {
    parts.push(text.slice(at, m.index));
    const color = m[2] ? "text-accent-ui" : m[1] ? "text-success" : "text-warning-text";
    parts.push(
      <span key={m.index} className={color}>
        {m[1] ?? m[0]}
      </span>,
      m[2] ?? "",
    );
    at = m.index + m[0].length;
  }
  parts.push(text.slice(at));
  return (
    <pre className={className} id={id}>
      {parts}
    </pre>
  );
}
