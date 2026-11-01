package faye.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import faye.dsl.BlockSpec;
import faye.dsl.FlowCompiler;
import faye.engine.Handler;
import faye.engine.HandlerMeta;
import faye.engine.HandlerRegistry;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.ScreenRegistry;
import faye.engine.testing.FixedIssuers;
import faye.ir.Flow;
import faye.ir.HookDef;
import faye.ir.IrCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Handler metadata from {@code src/conformance/handlers.yaml}, and the flows the tests check. */
final class Fixtures {

    static final Path ROOT = Path.of(System.getProperty("faye.repoRoot"));
    static final Path CONFORMANCE = ROOT.resolve("src/conformance");

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
        Path file = ROOT.resolve("apps/server/faye-app/src/main/resources/flows/" + name + ".yaml");
        return yaml(name + ".yaml", read(file));
    }

    static Flow conformanceFlow(String name) {
        return IrCodec.readFlow(read(CONFORMANCE.resolve("flows/" + name + ".json")));
    }

    /** The blocks the app's identity module implements (ADR 0030), as faye.handlers' BuiltinBlock does. */
    record TestBlock(
            String id,
            String title,
            String handler,
            String screen,
            BlockSpec.Role role,
            Map<String, Object> fixed,
            Map<String, BlockSpec.Setting> settings)
            implements BlockSpec {}

    static final BlockSpec.Setting THREE = new BlockSpec.Setting(3, BlockSpec.Setting.Target.ATTEMPTS);

    private static final Map<String, BlockSpec> BASE_BLOCKS = Map.of(
            "identifier",
            new TestBlock(
                    "identifier",
                    "Identifier",
                    "identity.resolve",
                    "identifier",
                    BlockSpec.Role.IDENTIFIER,
                    Map.of(),
                    Map.of(
                            "passkey",
                            new BlockSpec.Setting(false, BlockSpec.Setting.Target.OPTION),
                            "password",
                            new BlockSpec.Setting(false, BlockSpec.Setting.Target.PASSWORD),
                            "attempts",
                            THREE)),
            "password",
            new TestBlock(
                    "password",
                    "Password",
                    "password.verify",
                    "password",
                    BlockSpec.Role.PASSWORD,
                    Map.of(),
                    Map.of("attempts", THREE)),
            "passkey",
            new TestBlock(
                    "passkey",
                    "Passkey",
                    "webauthn.assert",
                    "passkey",
                    BlockSpec.Role.CREDENTIAL,
                    Map.of(),
                    Map.of("attempts", new BlockSpec.Setting(1, BlockSpec.Setting.Target.ATTEMPTS))),
            "add-passkey",
            new TestBlock(
                    "add-passkey",
                    "Add passkey",
                    "webauthn.register",
                    "passkey",
                    BlockSpec.Role.ACTION,
                    Map.of("register", true),
                    Map.of()),
            "totp",
            new TestBlock(
                    "totp",
                    "TOTP",
                    "totp.verify",
                    "otp",
                    BlockSpec.Role.CREDENTIAL,
                    Map.of(),
                    Map.of("attempts", THREE)),
            "enroll-totp",
            new TestBlock(
                    "enroll-totp",
                    "Enroll TOTP",
                    "totp.enroll",
                    "otp_enroll",
                    BlockSpec.Role.CREDENTIAL,
                    Map.of(),
                    Map.of("attempts", THREE)),
            "email-code",
            new TestBlock(
                    "email-code",
                    "Email code",
                    "email_code.verify",
                    "code_sent",
                    BlockSpec.Role.CREDENTIAL,
                    Map.of("channel", "email"),
                    Map.of("attempts", THREE)),
            "sms-code",
            new TestBlock(
                    "sms-code",
                    "Text message code",
                    "sms_code.verify",
                    "code_sent",
                    BlockSpec.Role.CREDENTIAL,
                    Map.of("channel", "sms"),
                    Map.of("attempts", THREE)),
            "add-phone",
            new TestBlock(
                    "add-phone",
                    "Add phone",
                    "phone.enroll",
                    "phone_enroll",
                    BlockSpec.Role.ACTION,
                    Map.of(),
                    Map.of("attempts", THREE)),
            "consent",
            new TestBlock(
                    "consent", "Consent", "consent.record", "consent", BlockSpec.Role.CONSENT, Map.of(), Map.of()));

    /** The built in blocks, with QR code sign in's (ADR 0032). */
    static final Map<String, BlockSpec> BLOCKS = withQr();

    private static Map<String, BlockSpec> withQr() {
        Map<String, BlockSpec> all = new java.util.HashMap<>(BASE_BLOCKS);
        all.put(
                "qr",
                new TestBlock(
                        "qr",
                        "QR code",
                        "qr.redeem",
                        "qr",
                        BlockSpec.Role.CREDENTIAL,
                        Map.of(),
                        Map.of("attempts", THREE)));
        all.put(
                "qr-approve",
                new TestBlock(
                        "qr-approve",
                        "Approve QR code",
                        "qr.approve",
                        "qr_approve",
                        BlockSpec.Role.APPROVE,
                        Map.of(),
                        Map.of()));
        return Map.copyOf(all);
    }

    /** What the app tells the compiler about consent steps (ADR 0026) and its blocks (ADR 0030). */
    static final FlowCompiler.Options DSL =
            new FlowCompiler.Options(Map.of(), java.util.Set.of("consent"), java.util.Set.of("consent.record"), BLOCKS);

    static Flow yaml(String origin, String yaml) {
        return FlowCompiler.compile(origin, yaml, DSL);
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
