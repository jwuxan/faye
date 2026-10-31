package faye.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import faye.ir.Call;
import faye.ir.Choose;
import faye.ir.Fail;
import faye.ir.Flow;
import faye.ir.Hook;
import faye.ir.Node;
import faye.ir.Retry;
import faye.ir.Screen;
import faye.ir.Seq;
import faye.ir.SetVar;
import faye.ir.SourceRef;
import faye.ir.Step;
import faye.ir.Succeed;
import faye.ir.When;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer L1 of the checks (docs/roadmap/ir-spec.md, Checks): every handler and screen a flow names
 * is in the catalogs, every screen's options are ones it takes, and every call's args match its
 * handler's schema. The schema (L0) has
 * already checked the shape; the checker (L2) adds paths and policy. The engine runs this whenever
 * it runs a version, so a catalog that lost a handler fails flows closed instead of mid run.
 */
public final class Verifier {

    /** @param source where the offending node came from */
    public record Problem(SourceRef source, String message) {
        @Override
        public String toString() {
            return source + ": " + message;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    /** Compiled args schemas; there is one per handler, so this stays small. */
    private static final Map<JsonNode, JsonSchema> COMPILED = new ConcurrentHashMap<>();

    private Verifier() {}

    public static List<Problem> verify(Flow flow, HandlerRegistry handlers, ScreenRegistry screens) {
        List<Problem> out = new ArrayList<>();
        flow.entries().values().forEach(e -> walk(e.body(), handlers, screens, out));
        return out;
    }

    private static void walk(Node node, HandlerRegistry handlers, ScreenRegistry screens, List<Problem> out) {
        switch (node) {
            case Seq s -> s.children().forEach(c -> walk(c, handlers, screens, out));
            case Step s -> walk(s.body(), handlers, screens, out);
            case When w -> walk(w.body(), handlers, screens, out);
            case Retry r -> walk(r.body(), handlers, screens, out);
            case Choose c -> {
                if (c.options().size() > 1
                        && screens.find(ScreenRegistry.METHOD_PICKER).isEmpty()) {
                    out.add(new Problem(
                            c.source(),
                            "a choose waits on screen '" + ScreenRegistry.METHOD_PICKER
                                    + "', which is not registered"));
                }
                c.options().forEach(o -> walk(o.body(), handlers, screens, out));
            }
            case Screen s -> {
                var spec = screens.find(s.type());
                if (spec.isEmpty()) {
                    out.add(new Problem(s.source(), "unknown screen type '" + s.type() + "'"));
                } else {
                    s.opts().keySet().stream()
                            .filter(k -> !spec.get().options().contains(k))
                            .sorted()
                            .forEach(k -> out.add(new Problem(
                                    s.source(),
                                    "screen '" + s.type() + "' has no option '" + k + "'; it takes "
                                            + spec.get().options().stream()
                                                    .sorted()
                                                    .toList())));
                }
            }
            case Call c -> {
                var handler = handlers.find(c.handler());
                if (handler.isEmpty()) {
                    out.add(new Problem(c.source(), "unknown handler '" + c.handler() + "'"));
                } else {
                    JsonSchema schema =
                            COMPILED.computeIfAbsent(handler.get().meta().args(), SCHEMAS::getSchema);
                    schema.validate(JSON.valueToTree(c.args()))
                            .forEach(m -> out.add(
                                    new Problem(c.source(), "args of '" + c.handler() + "': " + m.getMessage())));
                }
            }
            case Hook h -> {}
            case SetVar s -> {}
            case Succeed s -> {}
            case Fail f -> {}
        }
    }
}
