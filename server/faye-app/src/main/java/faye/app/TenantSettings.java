package faye.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Set;
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
 */
record TenantSettings(
        boolean mfaRequired,
        @Nullable Set<String> handlers,
        @Nullable Set<String> screens,
        Branding branding,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Map<String, Object>> blocks,
        boolean rememberMe) {

    TenantSettings {
        // Absent from a request body or a saved row: no branding, no block defaults
        if (branding == null) branding = Branding.NONE;
        blocks = blocks == null ? Map.of() : Map.copyOf(blocks);
        handlers = handlers == null ? null : Set.copyOf(handlers);
        screens = screens == null ? null : Set.copyOf(screens);
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
}
