package faye.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The host contract: everything a {@link FlowHost} relays about a request, and the whole of what
 * flows and hooks read as {@code host.*} (docs/roadmap/ir-spec.md, Environment). The namespace is
 * closed: a host maps its protocol into these fields and cannot add one; the checker refuses a
 * predicate path {@code host.<x>} for any other {@code <x>} at publish. Adding a field is a change
 * to this contract, made through an ADR (ADR 0025).
 *
 * @param subject who the host already knows, for a step up or a consent; null when nobody
 * @param skip the host remembers this subject (login) or this grant (consent)
 * @param acrValues assurance the client asked for, most preferred first
 * @param requestedScope scopes the client asks for
 * @param requestedAccessTokenAudience audiences the client asks for
 * @param loginHint who the client expects to sign in; prefills the identifier
 * @param uiLocales the person's preferred languages, most preferred first
 * @param context the host's own data, carried from one request to the next (a consent receives
 *     what the login's accept returned); the only free form field
 */
public record HostParams(
        @Nullable String subject,
        boolean skip,
        List<String> acrValues,
        List<String> requestedScope,
        List<String> requestedAccessTokenAudience,
        @Nullable String loginHint,
        List<String> uiLocales,
        Map<String, Object> context) {

    /** The wire names of the fields: exactly the paths {@code host.<x>} a flow may read. */
    public static final Set<String> FIELDS = Set.of(
            "subject",
            "skip",
            "acr_values",
            "requested_scope",
            "requested_access_token_audience",
            "login_hint",
            "ui_locales",
            "context");

    /** The field whose own keys a flow may read below it, as {@code host.context.<key>}. */
    public static final String FREE_FORM = "context";

    public static final HostParams NONE =
            new HostParams(null, false, List.of(), List.of(), List.of(), null, List.of(), Map.of());

    public HostParams {
        subject = subject == null || subject.isEmpty() ? null : subject;
        loginHint = loginHint == null || loginHint.isEmpty() ? null : loginHint;
        acrValues = List.copyOf(acrValues);
        requestedScope = List.copyOf(requestedScope);
        requestedAccessTokenAudience = List.copyOf(requestedAccessTokenAudience);
        uiLocales = List.copyOf(uiLocales);
        // JSON null is a value the host may relay; Map.copyOf would refuse it
        context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /** Whether {@code host.<rest>} names a field of this contract. */
    public static boolean readable(String rest) {
        int dot = rest.indexOf('.');
        String field = dot < 0 ? rest : rest.substring(0, dot);
        return FIELDS.contains(field) && (dot < 0 || field.equals(FREE_FORM));
    }

    /**
     * Reads the wire form, as conformance vectors and tests write it.
     *
     * @throws IllegalArgumentException on a key that is not a field, or a value of the wrong type
     */
    public static HostParams fromMap(Map<String, ?> wire) {
        for (String key : wire.keySet()) {
            if (!FIELDS.contains(key)) throw new IllegalArgumentException("host has no field '" + key + "'");
        }
        return new HostParams(
                string(wire, "subject"),
                Boolean.TRUE.equals(typed(wire, "skip", Boolean.class)),
                strings(wire, "acr_values"),
                strings(wire, "requested_scope"),
                strings(wire, "requested_access_token_audience"),
                string(wire, "login_hint"),
                strings(wire, "ui_locales"),
                context(wire));
    }

    /**
     * What flows read as {@code host.*}, under the wire names. Absent strings are left out, so they
     * read as null; lists, {@code skip} and {@code context} are always present.
     */
    public Map<String, Object> toEnv() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (subject != null) out.put("subject", subject);
        out.put("skip", skip);
        out.put("acr_values", acrValues);
        out.put("requested_scope", requestedScope);
        out.put("requested_access_token_audience", requestedAccessTokenAudience);
        if (loginHint != null) out.put("login_hint", loginHint);
        out.put("ui_locales", uiLocales);
        out.put("context", context);
        return out;
    }

    private static @Nullable String string(Map<String, ?> wire, String key) {
        return typed(wire, key, String.class);
    }

    private static <T> @Nullable T typed(Map<String, ?> wire, String key, Class<T> type) {
        Object value = wire.get(key);
        if (value == null) return null;
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("host." + key + " must be a " + type.getSimpleName());
        }
        return type.cast(value);
    }

    /** A list of strings, or the space separated string OIDC encodes lists as ({@code acr_values}). */
    private static List<String> strings(Map<String, ?> wire, String key) {
        Object value = wire.get(key);
        if (value == null) return List.of();
        if (value instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s.trim().split("\\s+"));
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new IllegalArgumentException(
                    "host." + key + " must be a list of strings or a space separated string");
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static Map<String, Object> context(Map<String, ?> wire) {
        Object value = wire.get("context");
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("host.context must be an object");
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
