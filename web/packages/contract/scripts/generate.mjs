// Generates src/screen.ts from schema/screen.schema.json. CI fails if the output is stale.
import { readFile, writeFile } from "node:fs/promises";
import { compile } from "json-schema-to-typescript";

const schemaUrl = new URL("../../../../schema/screen.schema.json", import.meta.url);
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
