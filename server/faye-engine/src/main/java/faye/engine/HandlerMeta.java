package faye.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a handler declares about itself; the checker and the engine rely on it. The entries in
 * {@code conformance/handlers.yaml} are the contract, and {@link #fromYaml} is the one reader of
 * that format (docs/roadmap/ir-spec.md, Handler catalog).
 *
 * @param amr amr values this handler may prove when it succeeds; empty if it is not a credential
 * @param bindsSubject on success it names the subject, which must match any subject already bound
 * @param identifies it fills {@code user.*} from what the person typed; a credential handler runs
 *     only after one succeeded
 * @param requiresSubject it runs only once a credential verified the subject in this flow
 * @param effect what it changes outside the flow
 * @param args JSON Schema of the {@code call}'s args object
 */
public record HandlerMeta(
        Set<String> amr,
        boolean bindsSubject,
        boolean identifies,
        boolean requiresSubject,
        EffectKind effect,
        JsonNode args) {

    /** A handler that takes no arguments. */
    public static final JsonNode NO_ARGS =
            new ObjectMapper().createObjectNode().put("type", "object").put("additionalProperties", false);

    public enum EffectKind {
        /** Reads only. */
        NONE,
        /**
         * An atomic check and mark that is part of verification, made inline: idempotent for the
         * flow that makes it, refused for any other flow (a TOTP step).
         */
        GUARD,
        /**
         * A lasting change, returned as {@link Effect}s and applied through {@link Handler#commit}
         * only once the flow state that carries them is saved.
         */
        WRITE;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public HandlerMeta {
        amr = Set.copyOf(amr);
        args = args.deepCopy();
    }

    /** A credential: it proves some amr. */
    public boolean credential() {
        return !amr.isEmpty();
    }

    /**
     * Reads one entry of {@code conformance/handlers.yaml}. Absent flags are false, an absent effect
     * is {@code write} (the conservative value), absent args are {@link #NO_ARGS}.
     */
    public static HandlerMeta fromYaml(JsonNode entry) {
        Set<String> amr = new TreeSet<>();
        entry.path("amr").forEach(a -> amr.add(a.asText()));
        return new HandlerMeta(
                amr,
                entry.path("binds_subject").asBoolean(false),
                entry.path("identifies").asBoolean(false),
                entry.path("requires_subject").asBoolean(false),
                EffectKind.valueOf(entry.path("effect").asText("write").toUpperCase(Locale.ROOT)),
                entry.has("args") ? entry.get("args") : NO_ARGS);
    }
}
