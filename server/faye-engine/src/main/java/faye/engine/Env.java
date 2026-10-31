package faye.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Everything a flow can read. {@code auth} is written only by the engine, from credential
 * handlers that verified.
 */
public record Env(
        Map<String, Object> request,
        Map<String, Object> client,
        Map<String, Object> tenant,
        Map<String, Object> host,
        Map<String, Object> user,
        Map<String, Object> vars,
        Auth auth) {

    public record Verified(String handler, List<String> amr) {
        public Verified {
            java.util.Objects.requireNonNull(handler);
            amr = List.copyOf(amr);
        }
    }

    public record Auth(@Nullable String subject, List<Verified> verified) {
        public static final Auth NONE = new Auth(null, List.of());

        public Auth {
            verified = List.copyOf(verified);
        }

        /** amr values in the order they were first proven. */
        public List<String> amr() {
            Set<String> out = new LinkedHashSet<>();
            verified.forEach(v -> out.addAll(v.amr()));
            return List.copyOf(out);
        }

        /** @return null when nothing verified */
        public @Nullable String acr() {
            return Acr.of(amr());
        }
    }

    public Env {
        java.util.Objects.requireNonNull(auth);
        request = Map.copyOf(request);
        client = Map.copyOf(client);
        // State saved before tenant settings existed has none
        tenant = tenant == null ? Map.of() : Map.copyOf(tenant);
        host = Map.copyOf(host);
        user = Map.copyOf(user);
        vars = Map.copyOf(vars);
    }

    /**
     * The value at a predicate path such as {@code user.has_totp} or {@code host.acr_values}, or
     * null. {@code auth} exposes {@code subject}, {@code amr} and {@code acr}; {@code tenant} is the
     * tenant's {@link TenantPolicy#env()} when the flow started.
     */
    public @Nullable Object lookup(String path) {
        String[] parts = path.split("\\.");
        Object value = switch (parts[0]) {
            case "request" -> request;
            case "client" -> client;
            case "tenant" -> tenant;
            case "host" -> host;
            case "user" -> user;
            case "vars" -> vars;
            case "auth" -> {
                Map<String, Object> view = new HashMap<>();
                if (auth.subject() != null) view.put("subject", auth.subject());
                view.put("amr", auth.amr());
                if (auth.acr() != null) view.put("acr", auth.acr());
                yield view;
            }
            default -> null;
        };
        for (int i = 1; i < parts.length && value != null; i++) {
            value = value instanceof Map<?, ?> m ? m.get(parts[i]) : null;
        }
        return value;
    }

    Env withVar(String name, Object value) {
        Map<String, Object> merged = new HashMap<>(vars);
        merged.put(name, value);
        return new Env(request, client, tenant, host, user, merged, auth);
    }

    Env withVars(Map<String, Object> patch) {
        Map<String, Object> merged = new HashMap<>(vars);
        merged.putAll(patch);
        return new Env(request, client, tenant, host, user, merged, auth);
    }

    Env withUser(Map<String, Object> patch) {
        Map<String, Object> merged = new HashMap<>(user);
        merged.putAll(patch);
        return new Env(request, client, tenant, host, merged, vars, auth);
    }

    Env withVerified(@Nullable String subject, Verified verified) {
        List<Verified> all = new ArrayList<>(auth.verified());
        all.add(verified);
        return new Env(request, client, tenant, host, user, vars, new Auth(subject, all));
    }
}
