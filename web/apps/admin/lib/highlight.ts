import { yamlLanguage } from "@codemirror/lang-yaml";
import { classHighlighter, highlightCode } from "@lezer/highlight";

/** A run of text and the tok-* classes it is drawn with ("" for plain text). */
export type Token = { text: string; cls: string };

/**
 * YAML split into lines of highlighted tokens, for read only views. The whole text is parsed at
 * once, so block scalars and comments highlight as in the editor; colors come from the tok-*
 * rules in globals.css.
 */
export function highlightLines(text: string): Token[][] {
  const lines: Token[][] = [[]];
  highlightCode(
    text,
    yamlLanguage.parser.parse(text),
    classHighlighter,
    (t, cls) => lines[lines.length - 1].push({ text: t, cls }),
    () => lines.push([]),
  );
  return lines;
}
