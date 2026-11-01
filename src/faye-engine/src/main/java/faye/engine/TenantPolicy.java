package faye.engine;

import faye.ir.Entry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A tenant's settings that bound its flows. The checker enforces all of them when a version is
 * published; the engine enforces {@code mfaRequired} again when a login succeeds, and the hook
 * runner enforces {@code egress} again on every call.
 *
 * @param mfaRequired every sign in must reach {@code faye:2fa}
 * @param handlers handlers this tenant's flows may call; null for all
 * @param screens screen types this tenant's flows may show; null for all
 * @param egress named HTTP targets this tenant's hooks may call; none unless granted
 * @param name the tenant's display name, read as {@code tenant.name} (an authenticator app labels
 *     an enrolled secret with it); null when it has none
 * @param rememberMe the identifier screen offers "keep me signed in", read as {@code
 *     tenant.remember_me} (ADR 0033); a person who ticks it has the host remember the sign in
 * @param flows the settings of its flows by entry kind (ADR 0035); a kind not listed has {@link
 *     FlowSettings#NONE}
 */
public record TenantPolicy(
        boolean mfaRequired,
        @Nullable Set<String> handlers,
        @Nullable Set<String> screens,
        Set<String> egress,
        @Nullable String name,
        boolean rememberMe,
        Map<Entry.Kind, FlowSettings> flows) {

    public static final TenantPolicy DEFAULT = new TenantPolicy(false, null, null);

    public TenantPolicy(
            boolean mfaRequired,
            @Nullable Set<String> handlers,
            @Nullable Set<String> screens,
            Set<String> egress,
            @Nullable String name,
            boolean rememberMe) {
        this(mfaRequired, handlers, screens, egress, name, rememberMe, Map.of());
    }

    public TenantPolicy(
            boolean mfaRequired,
            @Nullable Set<String> handlers,
            @Nullable Set<String> screens,
            Set<String> egress,
            @Nullable String name) {
        this(mfaRequired, handlers, screens, egress, name, false);
    }

    public TenantPolicy(
            boolean mfaRequired, @Nullable Set<String> handlers, @Nullable Set<String> screens, Set<String> egress) {
        this(mfaRequired, handlers, screens, egress, null);
    }

    public TenantPolicy(boolean mfaRequired, @Nullable Set<String> handlers, @Nullable Set<String> screens) {
        this(mfaRequired, handlers, screens, Set.of());
    }

    public TenantPolicy {
        handlers = handlers == null ? null : Set.copyOf(handlers);
        screens = screens == null ? null : Set.copyOf(screens);
        egress = Set.copyOf(egress);
        flows = Map.copyOf(flows);
    }

    /** The settings of this tenant's entries of {@code kind}. */
    public FlowSettings flow(Entry.Kind kind) {
        return flows.getOrDefault(kind, FlowSettings.NONE);
    }

    /** What flows read as {@code tenant.*}. */
    public Map<String, Object> env() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mfa_required", mfaRequired);
        if (name != null) out.put("name", name);
        // Only when offered, so a tenant without it reads exactly as before
        if (rememberMe) out.put("remember_me", true);
        return out;
    }
}
