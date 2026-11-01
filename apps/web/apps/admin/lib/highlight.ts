import { javascriptLanguage, typescriptLanguage } from "@codemirror/lang-javascript";
import { yamlLanguage } from "@codemirror/lang-yaml";
import { classHighlighter, highlightCode } from "@lezer/highlight";

/** A run of text and the tok-* classes it is drawn with ("" for plain text). */
export type Token = { text: string; cls: string };

export type HighlightLang = "yaml" | "js" | "javascript" | "ts" | "typescript" | "json";

const PARSERS: Record<HighlightLang, (text: string) => ReturnType<typeof yamlLanguage.parser.parse>> = {
  yaml: (text) => yamlLanguage.parser.parse(text),
  js: (text) => javascriptLanguage.parser.parse(text),
  javascript: (text) => javascriptLanguage.parser.parse(text),
  ts: (text) => typescriptLanguage.parser.parse(text),
  typescript: (text) => typescriptLanguage.parser.parse(text),
  // Closest parser at hand: JSON highlights fine as a JavaScript literal
  json: (text) => javascriptLanguage.parser.parse(text),
};

/**
 * Source split into lines of highlighted tokens, for read only views. The whole text is parsed at
 * once, so block scalars and comments highlight as in the editor; colors come from the tok-*
 * rules in globals.css. Defaults to YAML.
 */
export function highlightLines(text: string, lang: HighlightLang = "yaml"): Token[][] {
  const lines: Token[][] = [[]];
  highlightCode(
    text,
    PARSERS[lang](text),
    classHighlighter,
    (t, cls) => lines[lines.length - 1].push({ text: t, cls }),
    () => lines.push([]),
  );
  return lines;
}
