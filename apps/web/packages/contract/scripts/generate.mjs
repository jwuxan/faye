// Generates src/screen.ts from schema/screen.schema.json, and src/ir.ts, the IR grammar facts the
// Admin UI checks as you type, from schema/ir.schema.json. CI fails if the output is stale.
import { readFile, writeFile } from "node:fs/promises";
import { compile } from "json-schema-to-typescript";

const schemaUrl = new URL("../../../../../src/schema/screen.schema.json", import.meta.url);
const schema = JSON.parse(await readFile(schemaUrl, "utf8"));

// One declaration per $defs entry (requests are not reachable from the root), then the root union.
const options = { bannerComment: "", additionalProperties: false, declareExternallyReferenced: false };
const parts = [];
for (const [name, def] of Object.entries(schema.$defs)) {
  parts.push(await compile({ ...def, $defs: schema.$defs, title: name }, name, options));
}
parts.push(await compile({ ...schema, title: "ScreenProtocolResponse" }, "ScreenProtocolResponse", options));

const banner = "/* Generated from schema/screen.schema.json by `pnpm generate`. Do not edit. */\n\n";
await writeFile(new URL("../src/screen.ts", import.meta.url), banner + parts.map((p) => p.trim()).join("\n\n") + "\n");

// The IR's grammar facts, read from its schema, so the editor holds no copy of them (ADR 0024).
const ir = JSON.parse(await readFile(new URL("../../../../../src/schema/ir.schema.json", import.meta.url), "utf8"));
const d = ir.$defs;
const namespaces = /^\^\(([a-z|]+)\)/.exec(d.path.pattern)?.[1].split("|");
if (!namespaces) throw new Error("schema/ir.schema.json: $defs/path.pattern no longer starts with (namespace|...)");
const operators = d.predicate.else.propertyNames.enum;
const refOf = (op) => d.predicate.else.properties[op].$ref;
const comparisons = operators.filter((op) => ["#/$defs/comparison", "#/$defs/ordering"].includes(refOf(op)));
const orderings = operators.filter((op) => refOf(op) === "#/$defs/ordering");
const constant = (name, doc, value) => `/** ${doc} */\nexport const ${name} = ${JSON.stringify(value)} as const;\n`;
const irTs = [
  "/* Generated from schema/ir.schema.json by `pnpm generate`. Do not edit. */\n",
  constant("ENTRY_KINDS", "Kinds of entry.", d.entry.prefixItems[2].enum),
  constant("ID_PATTERN", "Step, option, entry and hook ids, screen types and failure reasons.", d.id.pattern),
  constant("KEY_PATTERN", "Names of vars.", d.key.pattern),
  constant("PATH_PATTERN", "A path a predicate reads.", d.path.pattern),
  constant("NAMESPACES", "The first segment of a path.", namespaces),
  constant("OPERATORS", "Predicate operators.", operators),
  constant("COMPARISONS", "Operators that compare a path with a literal.", comparisons),
  constant("ORDERINGS", "Comparisons whose literal is an integer.", orderings),
].join("\n");
await writeFile(new URL("../src/ir.ts", import.meta.url), irTs);
