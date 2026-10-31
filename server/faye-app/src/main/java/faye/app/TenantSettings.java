package faye.app;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 */
record TenantSettings(
        boolean mfaRequired,
        @Nullable Set<String> handlers,
        @Nullable Set<String> screens,
        Branding branding) {

    TenantSettings {
        // Absent from a request body: no branding
        if (branding == null) branding = Branding.NONE;
        handlers = handlers == null ? null : Set.copyOf(handlers);
        screens = screens == null ? null : Set.copyOf(screens);
    }

    /**
     * How the Login UI dresses the tenant's typed screens. Every field is optional.
     *
     * @param name shown above every screen
     * @param logoUrl an https image URL
     * @param primaryColor {@code #rrggbb}, for the primary button and focus ring
     * @param radius corner radius in px, 0 to 24
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Branding(
            @Nullable String name,
            @Nullable String logoUrl,
            @Nullable String primaryColor,
            @Nullable Integer radius) {

        static final Branding NONE = new Branding(null, null, null, null);

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
