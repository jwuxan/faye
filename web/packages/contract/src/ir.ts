/* Generated from schema/ir.schema.json by `pnpm generate`. Do not edit. */

/** Kinds of entry. */
export const ENTRY_KINDS = ["authenticate","consent"] as const;

/** Step, option, entry and hook ids, screen types and failure reasons. */
export const ID_PATTERN = "^[a-z][a-z0-9_-]*$" as const;

/** Names of vars. */
export const KEY_PATTERN = "^[a-z][a-z0-9_]*$" as const;

/** A path a predicate reads. */
export const PATH_PATTERN = "^(request|client|host|tenant|user|vars|auth)(\\.[a-z][a-z0-9_]*)+$" as const;

/** The first segment of a path. */
export const NAMESPACES = ["request","client","host","tenant","user","vars","auth"] as const;

/** Predicate operators. */
export const OPERATORS = ["and","or","not","exists","eq","ne","has","gt","lt"] as const;

/** Operators that compare a path with a literal. */
export const COMPARISONS = ["eq","ne","has","gt","lt"] as const;

/** Comparisons whose literal is an integer. */
export const ORDERINGS = ["gt","lt"] as const;
