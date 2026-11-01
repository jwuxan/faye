"use client";

import { autocompletion, type CompletionContext, type CompletionResult } from "@codemirror/autocomplete";
import { javascript, javascriptLanguage } from "@codemirror/lang-javascript";
import { yaml, yamlLanguage } from "@codemirror/lang-yaml";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { type Diagnostic, lintGutter, setDiagnostics } from "@codemirror/lint";
import { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { tags as t } from "@lezer/highlight";
import { basicSetup } from "codemirror";
import { useEffect, useRef } from "react";
import { HOOK_GLOBALS, type Member } from "../../../../lib/hook-contract";
import { yamlCompletions } from "../../../../lib/yaml-complete";

export type Mark = { line: number; column: number; message: string; severity: "error" | "warning" };

const theme = EditorView.theme({
  "&": { fontSize: "var(--text-sm)", height: "100%", backgroundColor: "var(--background)", color: "var(--foreground)" },
  ".cm-content, .cm-gutters": { fontFamily: "var(--font-mono)" },
  ".cm-content": { padding: "8px 0" },
  ".cm-gutters": { backgroundColor: "var(--surface-2)", color: "var(--muted-foreground)", borderRight: "1px solid var(--border)" },
  ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "color-mix(in oklch, var(--muted) 60%, transparent)" },
  "&.cm-focused": { outline: "none" },
  ".cm-scroller": { overflow: "auto" },
  ".cm-cursor": { borderLeftColor: "var(--foreground)" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": { backgroundColor: "color-mix(in oklch, var(--accent-ui) 22%, transparent) !important" },
  ".cm-foldPlaceholder": { backgroundColor: "var(--muted)", border: "none", color: "var(--muted-foreground)" },
  ".cm-tooltip": { backgroundColor: "var(--popover)", color: "var(--popover-foreground)", border: "1px solid var(--border)", borderRadius: "var(--radius-md)" },
  ".cm-tooltip.cm-tooltip-autocomplete > ul": { fontFamily: "var(--font-mono)", fontSize: "var(--text-xs)", padding: "4px" },
  ".cm-tooltip.cm-tooltip-autocomplete > ul > li": { borderRadius: "var(--radius-sm)", padding: "2px 6px" },
  ".cm-tooltip-autocomplete ul li[aria-selected]": { backgroundColor: "var(--accent)", color: "var(--accent-foreground)" },
  ".cm-completionDetail": { color: "var(--muted-foreground)", fontFamily: "var(--font-sans)", fontStyle: "normal", marginLeft: "12px" },
  ".cm-completionMatchedText": { textDecoration: "none", fontWeight: "600", color: "var(--accent-ui)" },
});

/** Completes keys, block ids, hook ids, block settings and condition paths in flow YAML (lib/yaml-complete). */
function flowCompletions(context: CompletionContext): CompletionResult | null {
  const r = yamlCompletions(context.state.doc.toString(), context.pos, context.explicit);
  return r && { from: r.from, options: r.options, validFor: /^[\w.:-]*$/ };
}

/** Syntax colors from the theme tokens, so light and dark both read well. */
const highlight = HighlightStyle.define([
  { tag: [t.propertyName, t.definition(t.propertyName)], color: "var(--accent-ui)" },
  { tag: [t.keyword, t.operatorKeyword, t.controlKeyword, t.modifier], color: "var(--warm)", fontWeight: "500" },
  { tag: [t.string, t.special(t.string)], color: "var(--success)" },
  { tag: [t.number, t.bool, t.null, t.atom], color: "var(--warning-text)" },
  { tag: [t.comment, t.lineComment, t.blockComment], color: "var(--muted-foreground)", fontStyle: "italic" },
  { tag: [t.variableName, t.content], color: "var(--foreground)" },
  { tag: [t.punctuation, t.separator, t.bracket, t.meta], color: "var(--muted-foreground)" },
]);

/** Completes ctx.* and cap.* members in hook source. */
function hookCompletions(context: CompletionContext): CompletionResult | null {
  const word = context.matchBefore(/(ctx|cap)(\.[a-z_]*)*/);
  if (!word || (word.from === word.to && !context.explicit)) return null;
  const parts = word.text.split(".");
  let members: Record<string, Member> | undefined = HOOK_GLOBALS;
  for (const p of parts.slice(0, -1)) members = members?.[p]?.children;
  if (!members) return null;
  return {
    from: word.to - parts[parts.length - 1].length,
    options: Object.entries(members).map(([name, m]) => ({ label: name, detail: m.detail, type: "property" })),
  };
}

/** CodeMirror for YAML or hook JavaScript, with completions and the server's diagnostics as marks. */
export function CodeEditor({
  value,
  onChange,
  language,
  marks = [],
  readOnly = false,
  label,
  className,
}: {
  value: string;
  onChange?: (value: string) => void;
  language: "yaml" | "javascript";
  marks?: Mark[];
  readOnly?: boolean;
  label: string;
  className?: string;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const changed = useRef(onChange);
  useEffect(() => {
    changed.current = onChange;
  });

  useEffect(() => {
    const v = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          theme,
          syntaxHighlighting(highlight),
          language === "yaml"
            ? [yaml(), yamlLanguage.data.of({ autocomplete: flowCompletions }), autocompletion()]
            : [javascript(), javascriptLanguage.data.of({ autocomplete: hookCompletions }), autocompletion()],
          lintGutter(),
          EditorState.readOnly.of(readOnly),
          EditorView.contentAttributes.of({ "aria-label": label }),
          EditorView.updateListener.of((u) => {
            if (u.docChanged && !u.transactions.some((t) => t.isUserEvent("faye.external"))) {
              changed.current?.(u.state.doc.toString());
            }
          }),
        ],
      }),
    });
    view.current = v;
    return () => v.destroy();
    // Created once; value and marks are pushed in below
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const v = view.current;
    if (v && v.state.doc.toString() !== value) {
      v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value }, userEvent: "faye.external" });
    }
  }, [value]);

  useEffect(() => {
    const v = view.current;
    if (!v) return;
    const doc = v.state.doc;
    const diagnostics: Diagnostic[] = marks
      .filter((m) => m.line >= 1 && m.line <= doc.lines)
      .map((m) => {
        const line = doc.line(m.line);
        const from = Math.min(line.from + Math.max(m.column - 1, 0), line.to);
        return { from, to: Math.max(from, line.to), severity: m.severity, message: m.message };
      });
    v.dispatch(setDiagnostics(v.state, diagnostics));
  }, [marks, value]);

  return <div ref={host} className={className} />;
}
