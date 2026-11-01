package faye.app;

import static org.assertj.core.api.Assertions.assertThat;

import faye.dsl.BlockSpec;
import faye.dsl.BlockSpec.Role;
import faye.dsl.BlockSpec.Setting.Target;
import faye.engine.HandlerMeta;
import faye.engine.HandlerRegistry;
import faye.engine.ScreenRegistry;
import faye.engine.ScreenSpec;
import faye.identity.IdentityBlock;
import faye.ir.Entry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0030: each block names a registered handler and a registered screen, with options that
 * screen takes, and a role its handler's metadata agrees with, so the surface never promises what
 * the catalogs do not do.
 */
@SpringBootTest
class BlocksTest {

    @Autowired
    HandlerRegistry handlers;

    @Autowired
    ScreenRegistry screens;

    /** {@code conformance/blocks.yaml} is the block contract: the identity module implements exactly it. */
    @Test
    void identityBlocksAreTheContract() throws java.io.IOException {
        com.fasterxml.jackson.databind.JsonNode contract = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper()
                .readTree(java.nio.file.Path.of(System.getProperty("faye.repoRoot"), "conformance/blocks.yaml")
                        .toFile());
        java.util.Map<String, Object> expected = new java.util.TreeMap<>();
        contract.properties().forEach(e -> {
            com.fasterxml.jackson.databind.JsonNode b = e.getValue();
            java.util.Map<String, Object> settings = new java.util.TreeMap<>();
            b.path("settings")
                    .properties()
                    .forEach(s -> settings.put(
                            s.getKey(),
                            s.getValue().get("default").asText() + "/"
                                    + s.getValue().get("target").asText()));
            java.util.Map<String, Object> fixed = new java.util.TreeMap<>();
            b.path("fixed")
                    .properties()
                    .forEach(f -> fixed.put(f.getKey(), f.getValue().asBoolean()));
            expected.put(
                    e.getKey(),
                    java.util.List.of(
                            b.get("title").asText(),
                            b.get("handler").asText(),
                            b.get("screen").asText(),
                            b.get("role").asText(),
                            fixed,
                            settings));
        });
        java.util.Map<String, Object> actual = new java.util.TreeMap<>();
        for (BlockSpec block : IdentityBlock.values()) {
            java.util.Map<String, Object> settings = new java.util.TreeMap<>();
            block.settings()
                    .forEach((name, s) -> settings.put(
                            name, s.byDefault() + "/" + s.target().name().toLowerCase(java.util.Locale.ROOT)));
            actual.put(
                    block.id(),
                    java.util.List.of(
                            block.title(),
                            block.handler(),
                            block.screen(),
                            block.role().name().toLowerCase(java.util.Locale.ROOT),
                            new java.util.TreeMap<>(block.fixed()),
                            settings));
        }
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void blocksAgreeWithTheCatalogs() {
        for (BlockSpec block : IdentityBlock.values()) {
            String name = block.id();
            HandlerMeta meta = handlers.find(block.handler()).orElseThrow().meta();
            ScreenSpec screen = screens.find(block.screen()).orElseThrow();
            assertThat(screen.options()).as(name).containsAll(block.fixed().keySet());
            // A setting that turns on a screen option names one the screen takes
            block.settings().forEach((setting, s) -> {
                if (s.target() != Target.ATTEMPTS) {
                    assertThat(screen.options()).as(name).contains(setting);
                }
            });
            assertThat(meta.identifies()).as(name).isEqualTo(block.role() == Role.IDENTIFIER);
            assertThat(meta.entries().equals(Set.of(Entry.Kind.CONSENT)))
                    .as(name)
                    .isEqualTo(block.role() == Role.CONSENT);
            assertThat(meta.credential())
                    .as(name)
                    .isEqualTo(block.role() == Role.PASSWORD || block.role() == Role.CREDENTIAL);
        }
        // The identifier asks for the password with the one password block
        assertThat(java.util.Arrays.stream(IdentityBlock.values()).filter(b -> b.role() == Role.PASSWORD))
                .singleElement()
                .extracting(BlockSpec::screen)
                .isEqualTo("password");
    }
}
