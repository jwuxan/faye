import { createSearchAPI } from "fumadocs-core/search/server";
import { searchIndexes } from "../../../lib/docs-site";

// The docs are runtime input (FAYE_DOCS_DIR)
export const dynamic = "force-dynamic";

/** The docs' search (the ⌘K dialog of /docs): every page in the sidebar, by heading and paragraph. */
export const { GET } = createSearchAPI("advanced", { indexes: () => searchIndexes() });
