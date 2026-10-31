package faye.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import faye.dsl.FlowCompiler;
import faye.engine.Handler;
import faye.engine.HandlerMeta;
import faye.engine.HandlerRegistry;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.ScreenRegistry;
import faye.ir.Flow;
import faye.ir.HookDef;
import faye.ir.IrCodec;
import faye.screens.FixedIssuers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Handler metadata from {@code conformance/handlers.yaml}, and the flows the tests check. */
final class Fixtures {

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
    static final Path CONFORMANCE = ROOT.resolve("conformance");

    private Fixtures() {}

    /** The built in screens, with issuers of fixed material. */
    static ScreenRegistry screens() {
        return FixedIssuers.registry();
    }

    /** Handlers that carry the real metadata and are never invoked. */
    static HandlerRegistry handlers() {
        List<Handler> out = new ArrayList<>();
        metadata()
                .forEach((name, m) -> out.add(new Handler() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public HandlerMeta meta() {
                        return HandlerMeta.fromYaml(m);
                    }

                    @Override
                    public Outcome invoke(Invocation call) {
                        throw new UnsupportedOperationException(name);
                    }
                }));
        return new HandlerRegistry(out);
    }

    static Map<String, JsonNode> metadata() {
        try {
            Map<String, JsonNode> out = new java.util.LinkedHashMap<>();
            new YAMLMapper()
                    .readTree(CONFORMANCE.resolve("handlers.yaml").toFile())
                    .properties()
                    .forEach(e -> out.put(e.getKey(), e.getValue()));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Conformance hooks are scripted, so any hash stands for a stored hook with no capabilities.
     */
    static Optional<HookDef> hook(String hash) {
        return Optional.of(new HookDef("export default () => ({})", List.of(), HookDef.DEFAULT_TIMEOUT_MS));
    }

    /** A tenant flow shipped with the app. */
    static Flow tenantFlow(String name) {
        Path file = ROOT.resolve("server/faye-app/src/main/resources/flows/" + name + ".yaml");
        return yaml(name + ".yaml", read(file));
    }

    static Flow conformanceFlow(String name) {
        return IrCodec.readFlow(read(CONFORMANCE.resolve("flows/" + name + ".json")));
    }

    static Flow yaml(String origin, String yaml) {
        return FlowCompiler.compile(origin, yaml);
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
