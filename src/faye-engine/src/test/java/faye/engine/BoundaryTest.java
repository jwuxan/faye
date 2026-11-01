package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The product under {@code src/} (the IR, the DSL, the hook sandbox, the engine, the checker and the
 * built in screens) knows no authorization server and no credential handler: it names neither in its
 * sources, and its build references nothing from {@code apps/} or a host or credential library
 * (charter principle 3, ADR 0024 invariant 4, ADR 0025).
 */
class BoundaryTest {

    private static final Path SRC = Path.of(System.getProperty("faye.repoRoot"), "src");

    /** Main source packages that must hold every naming rule. */
    private static final List<Path> BOUND = List.of(
            SRC.resolve("faye-core/src/main/java/faye/ir"),
            SRC.resolve("faye-core/src/main/java/faye/dsl"),
            SRC.resolve("faye-core/src/main/java/faye/hooks"),
            SRC.resolve("faye-engine/src/main/java/faye/engine"),
            SRC.resolve("faye-engine/src/main/java/faye/check"));

    /**
     * The screen protocol v1 catalog. It is bound too, exempt only from the rules it must break by
     * nature: it names the built in screens it defines, and reads the host parameters it presents
     * ({@code login_hint}, {@code requested_scope}) through {@link Env#host()}.
     */
    private static final Path SCREENS = SRC.resolve("faye-engine/src/main/java/faye/screens");

    /**
     * The built in handlers and issuers the engine ships ({@code faye.handlers}). Bound too, exempt
     * only from the built in names rule, which they must break by nature: their handler names,
     * screen names and failure codes are exactly what it forbids.
     */
    private static final Path HANDLERS = SRC.resolve("faye-engine/src/main/java/faye/handlers");

    /** The engine's SPI packages: what a host implements. */
    private static final Path SPI = SRC.resolve("faye-engine/src/main/java/faye/engine/spi");

    /** The host contract is the one place the protocol's parameter names are written down. */
    private static final String CONTRACT = "HostParams.java";

    /** The client contract names its fields, metadata among them; nothing else in the core does. */
    private static final String CLIENT_CONTRACT = "HostRequest.java";

    private static final Pattern BUILTIN_NAMES =
            Pattern.compile("\"(consent\\.record|totp\\.\\w+|webauthn\\.\\w+|password\\.verify|identity\\.resolve"
                    + "|register|passkey|otp_enroll|identifier|password|challenge|secret)\"");

    private static final Pattern TOKEN_CLAIMS =
            Pattern.compile("\"(faye_flow_id|email_verified)\"|id_?token", Pattern.CASE_INSENSITIVE);

    private static final Pattern HOST_PARAMS = Pattern.compile("\"(login_hint|requested_scope|ui_locales)\"");

    /** Libraries only an app, a host or a credential module may name. */
    private static final Pattern APP_LIBRARY =
            Pattern.compile("hydra|webauthn|spring|bouncycastle|bcprov|sqlite|flyway|apps/", Pattern.CASE_INSENSITIVE);

    @Test
    void theCoreNamesNoHostAndNoScreen() {
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            String text = read(file);
            String lower = text.toLowerCase(Locale.ROOT);
            boolean screens = file.startsWith(SCREENS);
            boolean handlers = file.startsWith(HANDLERS);
            if (lower.contains("hydra")) found.add(file + ": hydra");
            if (lower.contains("otpauth")) found.add(file + ": otpauth");
            // The authorization server's clients are the admin console's business (apps' faye-clients)
            if (text.contains("ClientDirectory") || text.contains("redirectUris")) {
                found.add(file + ": the authorization server's client registry");
            }
            if (text.contains("PASSKEY_ALGORITHMS")) found.add(file + ": PASSKEY_ALGORITHMS");
            if (text.contains("\"Faye\"")) found.add(file + ": \"Faye\"");
            // Screens and handlers say what they need through the catalogs (ScreenSpec, HandlerMeta)
            if (!screens && !handlers && BUILTIN_NAMES.matcher(text).find()) {
                found.add(file + ": a built in screen, option or handler by name");
            }
            // Tenant and preview come typed from the host; claims are the host's (ADR 0025)
            if (text.contains("\"metadata\"") && !file.endsWith(CLIENT_CONTRACT)) {
                found.add(file + ": reads client metadata");
            }
            // faye.handlers emits the user.* output "email_verified" (IdentityHandlers always did);
            // only that literal is exempted there
            String claims = handlers ? text.replace("\"email_verified\"", "\"\"") : text;
            if (TOKEN_CLAIMS.matcher(claims).find()) {
                found.add(file + ": token claims");
            }
            if (!screens
                    && !file.endsWith(CONTRACT)
                    && HOST_PARAMS.matcher(text).find()) {
                found.add(file + ": a host parameter outside the host contract");
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void theEngineAndCheckerDoNotSeeTheScreensAndTheScreensDoNotSeeTheChecker() {
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            boolean screens = file.startsWith(SCREENS);
            boolean handlers = file.startsWith(HANDLERS);
            boolean spi = file.startsWith(SPI);
            for (String line : read(file).lines().toList()) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) continue;
                if (!screens && trimmed.contains("faye.screens")) found.add(file + ": " + trimmed);
                if (screens && trimmed.contains("faye.check")) found.add(file + ": " + trimmed);
                // The engine, checker and SPI never reach the built in handlers' package
                if (!handlers && trimmed.contains("faye.handlers")) found.add(file + ": " + trimmed);
                // The SPI stands alone: the IR, the engine's own types, the JDK, nullness markers;
                // ScreenView's Jackson annotations mark its JSON view and carry no databind
                if (spi
                        && !trimmed.matches("import (faye\\.ir\\..*|faye\\.engine\\..*|java\\..*|javax\\..*"
                                + "|org\\.jspecify\\..*|com\\.fasterxml\\.jackson\\.annotation\\..*);")) {
                    found.add(file + ": " + trimmed);
                }
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void theProductBuildReferencesNothingOutsideIt() {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            files.filter(f -> {
                        String name = f.toString();
                        return (name.endsWith(".gradle.kts") || name.endsWith(".toml")) && !name.contains("/build/");
                    })
                    .forEach(f -> {
                        String text = read(f);
                        if (APP_LIBRARY.matcher(text).find()) {
                            found.add(f + ": an app, host or credential library");
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(found).isEmpty();
    }

    private static List<Path> sources() {
        List<Path> out = new ArrayList<>();
        for (Path dir :
                Stream.concat(BOUND.stream(), Stream.of(SCREENS, HANDLERS)).toList()) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(out).isNotEmpty();
        return out;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
