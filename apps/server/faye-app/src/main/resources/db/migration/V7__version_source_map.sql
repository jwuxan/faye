-- Where each node of a version came from (ADR 0027): IR path to line and column, built from the
-- compiled flow and stored with the YAML in faye_version_source, keyed by the same hash. The IR and
-- its hash carry no positions, so reformatting YAML still makes no new version. Versions stored
-- before this table get a map at startup when their YAML still compiles to their hash.
CREATE TABLE IF NOT EXISTS faye_version_source_map (
  hash TEXT PRIMARY KEY,
  map TEXT NOT NULL
);
