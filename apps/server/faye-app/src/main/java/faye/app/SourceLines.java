package faye.app;

import faye.dsl.FlowCompiler;
import faye.engine.FlowVersion;
import faye.engine.Site;
import faye.engine.spi.FlowVersions;
import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Entry;
import faye.ir.Fail;
import faye.ir.Hook;
import faye.ir.Node;
import faye.ir.NodePaths;
import faye.ir.Retry;
import faye.ir.Screen;
import faye.ir.Seq;
import faye.ir.SetVar;
import faye.ir.SourceMap;
import faye.ir.SourceRef;
import faye.ir.Step;
import faye.ir.Succeed;
import faye.ir.When;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.io.Resource;

/**
 * Turns a {@link Site} the engine reports into the YAML line it came from, through the source map
 * of the version that ran (ADR 0027). Published versions' maps are stored with their YAML; a
 * previewed draft's map is kept here in memory while it is recent, since a draft is not published
 * and its lines are those of the YAML in the author's editor. A version without a map resolves to
 * nothing, and callers fall back to step ids.
 *
 * <p>Only the preview, admin and log channels use this; the screen API never does, so no line or
 * file name reaches the Login UI.
 */
final class SourceLines {

    /** Drafts' maps kept in memory, least recently remembered first out. */
    static final int DRAFTS = 64;

    private static final System.Logger LOG = System.getLogger(SourceLines.class.getName());

    /**
     * A resolved site.
     *
     * @param kind the YAML key of the node: {@code use}, {@code hook}, {@code screen}, {@code fail},
     *     {@code succeed}, {@code step}, {@code retry}, {@code if}, {@code alternative}, {@code set}
     *     or {@code steps}
     */
    record Located(String origin, int line, int column, String kind) {
        @Override
        public String toString() {
            return origin + ":" + line + ":" + column;
        }
    }

    private final TenantConfigStore store;
    private final FlowVersions versions;
    private final Map<String, SourceMap> drafts = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SourceMap> eldest) {
            return size() > DRAFTS;
        }
    };

    SourceLines(TenantConfigStore store, FlowVersions versions) {
        this.store = store;
        this.versions = versions;
    }

    /** Keeps the map of a draft that a preview client may run. */
    void remember(String versionHash, SourceMap map) {
        synchronized (drafts) {
            drafts.put(versionHash, map);
        }
    }

    /** Where {@code at} of version {@code versionHash} came from; empty when no map has a line for it. */
    Optional<Located> locate(String versionHash, Site at) {
        Optional<FlowVersion> version = versions.get(versionHash);
        if (version.isEmpty()) return Optional.empty();
        Entry entry = version.get().flow().entries().get(at.entry());
        if (entry == null) return Optional.empty();
        Optional<Node> node = NodePaths.find(entry.body(), at.path());
        if (node.isEmpty()) return Optional.empty();
        SourceMap draft;
        synchronized (drafts) {
            draft = drafts.get(versionHash);
        }
        Optional<SourceRef> ref = draft != null
                ? draft.find(at.entry(), at.path())
                : store.sourceMap(versionHash).flatMap(m -> m.find(at.entry(), at.path()));
        return ref.map(r -> new Located(r.origin(), r.line(), r.column(), kind(node.get())));
    }

    /**
     * Gives each version stored with YAML and no map (published before ADR 0027) the map of its
     * YAML, compiled again, if that still compiles to the version's hash; otherwise it keeps none.
     * Runs once at startup; nothing stored is rewritten.
     *
     * @param configured each tenant's configured flow, whose directory holds its hook files
     */
    void backfill(Map<String, Resource> configured, FlowCompiler.Options dsl) {
        for (String hash : store.unmappedSources()) {
            Optional<String> yaml = store.source(hash);
            Optional<String> tenant = configured.keySet().stream()
                    .filter(t -> versions.history(t).contains(hash))
                    .findFirst();
            if (yaml.isEmpty() || tenant.isEmpty()) continue;
            try {
                FlowCompiler.Compiled compiled = EngineConfiguration.compileDraft(
                        Objects.requireNonNull(configured.get(tenant.get())), tenant.get(), yaml.get(), dsl);
                if (FlowVersion.of(compiled.flow()).hash().equals(hash)) {
                    store.saveSourceMap(hash, SourceMap.of(compiled.flow()));
                } else {
                    LOG.log(
                            System.Logger.Level.INFO,
                            "version {0} no longer compiles from its YAML; it has no lines",
                            hash);
                }
            } catch (RuntimeException e) {
                // A DslException, or a hook file that is gone
                LOG.log(
                        System.Logger.Level.INFO,
                        "version {0} no longer compiles from its YAML; it has no lines",
                        hash);
            }
        }
    }

    /** The YAML key that lowers to this node. */
    static String kind(Node node) {
        return switch (node) {
            case Seq s -> "steps";
            case Step s -> "step";
            case Screen s -> "screen";
            case Call c -> "use";
            case Hook h -> "hook";
            case Choose c -> "alternative";
            case When w -> "if";
            case Retry r -> "retry";
            case SetVar s -> "set";
            case Succeed s -> "succeed";
            case Fail f -> "fail";
        };
    }
}
