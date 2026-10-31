package faye.app;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * @param loginUiUrl base URL of the Login UI; flows are rendered at {@code /flow/{flowId}}
 * @param flowTtl lifetime of a suspended flow; matches Hydra's challenge lifetime
 * @param tenants settings and YAML flow per tenant id; each flow is checked and published at startup
 * @param demoUsers accounts written into the user store at startup; tests use them, the shipped
 *     config has none (accounts come from the deploy seed)
 * @param hydraAdminUrl Hydra admin API base URL; when empty, challenges come from the in memory mock host
 * @param webauthn passkey relying party
 * @param adminToken bearer token for the admin API; when empty, the admin API is off
 * @param egress base URL per named HTTP target that hooks may call; a target with an empty URL is
 *     left out, so hooks calling it fail
 * @param previewEnabled preview clients (client metadata {@code preview: true}) run the tenant's
 *     draft; when off, their sign ins are refused
 */
@ConfigurationProperties("faye")
public record FayeProperties(
        String loginUiUrl,
        Duration flowTtl,
        Map<String, Tenant> tenants,
        List<DemoUser> demoUsers,
        String hydraAdminUrl,
        Webauthn webauthn,
        String adminToken,
        Map<String, String> egress,
        boolean previewEnabled) {

    /**
     * @param flow the tenant's YAML flow. Published at startup unless the tenant published it before,
     *     so a version published or rolled back to through the admin API stays current across restarts
     * @param mfaRequired every sign in must reach {@code faye:2fa}
     * @param handlers handlers the tenant's flows may call; all when unset
     * @param screens screen types the tenant's flows may show; all when unset
     * @param egress named HTTP targets the tenant's hooks may call; none when unset
     */
    public record Tenant(
            Resource flow,
            boolean mfaRequired,
            @Nullable Set<String> handlers,
            @Nullable Set<String> screens,
            @Nullable Set<String> egress) {}

    /**
     * @param totpSecret base32 TOTP secret already enrolled, or null
     * @param tenant the tenant whose user pool it is in; shared by every tenant when null
     */
    public record DemoUser(
            String subject,
            String identifier,
            @Nullable String name,
            String password,
            @Nullable String totpSecret,
            @Nullable String tenant) {}

    /**
     * @param rpId passkey scope, the Login UI's registrable domain
     * @param origins Login UI origins allowed to run passkey ceremonies
     */
    public record Webauthn(String rpId, Set<String> origins) {}
}
