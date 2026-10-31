package faye.ir;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code (entry id kind body)}: a body the host can start. Its kind decides which rules the
 * checker applies to it and what {@code succeed} means (docs/roadmap/ir-spec.md, Entries).
 */
public record Entry(Kind kind, Node body) {

    public enum Kind {
        /** Proves who the person is; succeeds only with a verified credential for the subject. */
        AUTHENTICATE,
        /** Grants a client access for a subject an authenticate entry already proved. */
        CONSENT;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> of(String wireName) {
            for (Kind k : values()) {
                if (k.wireName().equals(wireName)) return Optional.of(k);
            }
            return Optional.empty();
        }
    }
}
