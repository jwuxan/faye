package faye.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import faye.engine.FlowSettings;
import faye.ir.Entry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What an admin may change about a tenant at run time, over its {@code faye.tenants.<id>} defaults.
 * Egress grants are not here: which services tenant code may reach is operator config.
 *
 * @param mfaRequired every sign in must reach {@code faye:2fa}
 * @param handlers handlers the tenant's flows may call; null for all
 * @param screens screen types the tenant's flows may show; null for all
 * @param blocks defaults for the settings of the blocks its flows use, by block id and setting
 *     (ADR 0030); a flow's {@code with} still wins. Applied when a flow is compiled, so a published
 *     version keeps the defaults it was compiled with
 * @param rememberMe the identifier screen offers "keep me signed in" (ADR 0033); takes effect for
 *     flows that start after it is saved. Absent from rows saved before it existed: off
 * @param flows flow settings by entry kind ({@code authenticate}, {@code consent}; ADR 0035); take
 *     effect for flows that start after they are saved. Absent from rows saved before they existed,
 *     and for a kind not listed: no limit but {@code faye.flow-ttl}
 */
record TenantSettings(
        boolean mfaRequired,
        @Nullable Set<String> handlers,
        @Nullable Set<String> screens,
        Branding branding,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Map<String, Object>> blocks,
        boolean rememberMe,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, FlowLimits> flows) {

    TenantSettings {
        // Absent from a request body or a saved row: no branding, no block defaults
        if (branding == null) branding = Branding.NONE;
        blocks = blocks == null ? Map.of() : Map.copyOf(blocks);
        handlers = handlers == null ? null : Set.copyOf(handlers);
        screens = screens == null ? null : Set.copyOf(screens);
        // A kind with neither limit set has none, as if it were not listed
        Map<String, FlowLimits> set = new TreeMap<>();
        if (flows != null) {
            flows.forEach((kind, limits) -> {
                if (kind != null && limits != null && !limits.isEmpty()) set.put(kind, limits);
            });
        }
        flows = Map.copyOf(set);
    }

    /** Without flow settings. */
    TenantSettings(
            boolean mfaRequired,
            @Nullable Set<String> handlers,
            @Nullable Set<String> screens,
            Branding branding,
            Map<String, Map<String, Object>> blocks,
            boolean rememberMe) {
        this(mfaRequired, handlers, screens, branding, blocks, rememberMe, Map.of());
    }

    /** Without "keep me signed in". */
    TenantSettings(
            boolean mfaRequired,
            @Nullable Set<String> handlers,
            @Nullable Set<String> screens,
            Branding branding,
            Map<String, Map<String, Object>> blocks) {
        this(mfaRequired, handlers, screens, branding, blocks, false);
    }

    /** Without block defaults. */
    TenantSettings(
            boolean mfaRequired, @Nullable Set<String> handlers, @Nullable Set<String> screens, Branding branding) {
        this(mfaRequired, handlers, screens, branding, Map.of());
    }

    /**
     * How the Login UI dresses the tenant's typed screens. Every field is optional.
     *
     * @param name shown above every screen and in its footer
     * @param logoUrl an https image URL
     * @param primaryColor {@code #rrggbb}, for the primary button and focus ring
     * @param radius corner radius in px, 0 to 24
     * @param showPassword password fields have a button that shows what was typed; true or absent
     * @param signUp the identifier screen shows a sign up button, which says sign up is not supported
     *     yet; true or absent
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Branding(
            @Nullable String name,
            @Nullable String logoUrl,
            @Nullable String primaryColor,
            @Nullable Integer radius,
            @Nullable Boolean showPassword,
            @Nullable Boolean signUp) {

        static final Branding NONE = new Branding(null, null, null, null, null, null);

        /** Off is absent, so the Login UI receives only what the tenant turned on. */
        Branding {
            if (Boolean.FALSE.equals(showPassword)) showPassword = null;
            if (Boolean.FALSE.equals(signUp)) signUp = null;
        }

        Branding(
                @Nullable String name,
                @Nullable String logoUrl,
                @Nullable String primaryColor,
                @Nullable Integer radius) {
            this(name, logoUrl, primaryColor, radius, null, null);
        }

        private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
        private static final Pattern LOGO = Pattern.compile("https://[^\\s\"'<>]{1,500}");

        boolean isEmpty() {
            return equals(NONE);
        }

        /** Why this branding cannot be saved, or null. */
        @Nullable
        String problem() {
            if (name != null && (name.isBlank() || name.length() > 64)) return "name must be 1 to 64 characters";
            if (logoUrl != null && !LOGO.matcher(logoUrl).matches()) return "logoUrl must be an https URL";
            if (primaryColor != null && !COLOR.matcher(primaryColor).matches()) {
                return "primaryColor must be #rrggbb";
            }
            if (radius != null && (radius < 0 || radius > 24)) return "radius must be 0 to 24";
            return null;
        }
    }

    /** The engine's flow settings by entry kind; a kind the engine does not know is left out. */
    Map<Entry.Kind, FlowSettings> flowSettings() {
        Map<Entry.Kind, FlowSettings> out = new EnumMap<>(Entry.Kind.class);
        flows.forEach((kind, limits) -> Entry.Kind.of(kind).ifPresent(k -> out.put(k, limits.settings())));
        return out;
    }

    /** Why these flow settings cannot be saved under the deployment's maximum, or null. */
    @Nullable
    String flowsProblem(Duration max) {
        for (Map.Entry<String, FlowLimits> e : flows.entrySet()) {
            if (Entry.Kind.of(e.getKey()).isEmpty()) return "unknown entry kind in flows: " + e.getKey();
            String problem = e.getValue().problem(max);
            if (problem != null) return "flows." + e.getKey() + ": " + problem;
        }
        return null;
    }

    /**
     * How long a person has to finish an entry of one kind (ADR 0035), in seconds. Either may be
     * absent: no limit of that kind but the deployment's.
     *
     * @param lifetimeSeconds from the entry's start, 60 up to {@code faye.flow-ttl}
     * @param idleTimeoutSeconds from the last answer, 60 up to the lifetime (or {@code faye.flow-ttl})
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FlowLimits(
            @Nullable Integer lifetimeSeconds, @Nullable Integer idleTimeoutSeconds) {

        static final int MIN_SECONDS = 60;

        boolean isEmpty() {
            return lifetimeSeconds == null && idleTimeoutSeconds == null;
        }

        FlowSettings settings() {
            return new FlowSettings(
                    lifetimeSeconds == null ? null : Duration.ofSeconds(lifetimeSeconds),
                    idleTimeoutSeconds == null ? null : Duration.ofSeconds(idleTimeoutSeconds));
        }

        @Nullable
        String problem(Duration max) {
            long ceiling = max.toSeconds();
            if (lifetimeSeconds != null && (lifetimeSeconds < MIN_SECONDS || lifetimeSeconds > ceiling)) {
                return "lifetimeSeconds must be " + MIN_SECONDS + " to " + ceiling;
            }
            long idleCeiling = lifetimeSeconds == null ? ceiling : lifetimeSeconds;
            if (idleTimeoutSeconds != null && (idleTimeoutSeconds < MIN_SECONDS || idleTimeoutSeconds > idleCeiling)) {
                return "idleTimeoutSeconds must be " + MIN_SECONDS + " to " + idleCeiling;
            }
            return null;
        }
    }
}
