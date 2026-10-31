package faye.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The screens flows may show, by type, and the {@link CredentialIssuer} of each screen that shows
 * credential material. The engine knows no screen but {@link #METHOD_PICKER}, which a {@code choose}
 * waits on; an embedder registers the screens it renders. Construction refuses a screen that
 * declares an issuer ({@link ScreenSpec#issued()}) and has none, an issuer for a screen that
 * declares none or is not registered, and duplicates, so a misconfiguration fails at startup rather
 * than at the first flow.
 */
public final class ScreenRegistry {

    /** The screen a {@code choose} with more than one enabled option waits on (ir-spec.md, choose). */
    public static final String METHOD_PICKER = "method_picker";

    private final Map<String, ScreenSpec> screens = new LinkedHashMap<>();
    private final Map<String, CredentialIssuer> issuers = new LinkedHashMap<>();

    public ScreenRegistry(List<? extends ScreenSpec> screens, List<? extends CredentialIssuer> issuers) {
        for (ScreenSpec s : screens) {
            if (this.screens.put(s.type(), s) != null) {
                throw new IllegalArgumentException("duplicate screen " + s.type());
            }
            // The engine fills the picker itself, with the options of the choose
            if (s.type().equals(METHOD_PICKER) && s.issued()) {
                throw new IllegalArgumentException(METHOD_PICKER + " cannot declare an issuer");
            }
        }
        for (CredentialIssuer i : issuers) {
            ScreenSpec spec = this.screens.get(i.screen());
            if (spec == null || !spec.issued()) {
                throw new IllegalArgumentException("issuer for screen " + i.screen()
                        + (spec == null ? ", which is not registered" : ", which declares no issuer"));
            }
            if (this.issuers.put(i.screen(), i) != null) {
                throw new IllegalArgumentException("duplicate issuer for screen " + i.screen());
            }
        }
        var missing = new TreeSet<String>();
        this.screens.values().stream()
                .filter(ScreenSpec::issued)
                .map(ScreenSpec::type)
                .filter(t -> !this.issuers.containsKey(t))
                .forEach(missing::add);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("screens " + missing + " declare an issuer and have none registered");
        }
    }

    public Optional<ScreenSpec> find(String type) {
        return Optional.ofNullable(screens.get(type));
    }

    /** The issuer of a screen that declares one; empty for any other. */
    public Optional<CredentialIssuer> issuer(String type) {
        return Optional.ofNullable(issuers.get(type));
    }

    public Collection<ScreenSpec> all() {
        return screens.values();
    }
}
