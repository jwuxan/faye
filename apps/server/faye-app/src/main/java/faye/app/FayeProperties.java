package faye.app;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * @param loginUiUrl base URL of the Login UI; each screen is rendered at its path, with
 *     {@code ?flow={flowId}} (ADR 0029)
 * @param flowTtl lifetime of a suspended flow, kept by the store; matches Hydra's challenge lifetime.
 *     The maximum of each tenant's flow settings, which can only shorten it (ADR 0035)
 * @param tenants settings and YAML flow per tenant id; each flow is checked and published at startup
 * @param demoUsers accounts written into the user store at startup; tests use them, the shipped
 *     config has none (accounts come from the deploy seed)
 * @param hydraAdminUrl Hydra admin API base URL; when empty, challenges come from the in memory mock host
 * @param webauthn passkey relying party
 * @param adminToken bearer token for the admin API; when empty, the admin API is off
 * @param adminSocket path of the Unix domain socket the admin API is served on, and only there;
 *     when empty, the admin API is off
 * @param egress base URL per named HTTP target that hooks may call; a target with an empty URL is
 *     left out, so hooks calling it fail
 * @param previewEnabled preview clients (client metadata {@code preview: true}) run the tenant's
 *     draft; when off, their sign ins are refused
 * @param codeKey base64 key (at least 32 bytes) delivered one time codes are MACed with (ADR 0032);
 *     when empty, one is generated once and kept in the database
 * @param demoOutbox directory a code is written to, as a file, for every tenant channel without a
 *     webhook: the demo's and the tests' delivery. When empty, such a channel is off
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
        String adminSocket,
        Map<String, String> egress,
        boolean previewEnabled,
        @Nullable String codeKey,
        @Nullable String demoOutbox) {

    /**
     * @param flow the tenant's YAML flow. Published at startup unless the tenant published it before,
     *     so a version published or rolled back to through the admin API stays current across restarts
     * @param mfaRequired every sign in must reach {@code faye:2fa}
     * @param handlers handlers the tenant's flows may call; all when unset
     * @param screens screen types the tenant's flows may show; all when unset
     * @param egress named HTTP targets the tenant's hooks may call; none when unset
     * @param blocks defaults for its blocks' settings, by block id and setting ({@code password:
     *     { attempts: 5 }}); the blocks' own when unset (ADR 0030)
     * @param name the display name its login screens show (above every screen and in the footer)
     *     until an admin saves branding; none when unset
     * @param delivery where its one time codes go, by channel (ADR 0032); a channel with neither a
     *     webhook nor the demo outbox is off, and its blocks are refused at publish
     */
    public record Tenant(
            Resource flow,
            boolean mfaRequired,
            @Nullable Set<String> handlers,
            @Nullable Set<String> screens,
            @Nullable Set<String> egress,
            @Nullable Map<String, Map<String, Object>> blocks,
            @Nullable String name,
            @Nullable Delivery delivery) {}

    /** A tenant's code delivery by channel. */
    public record Delivery(
            @Nullable Webhook email, @Nullable Webhook sms) {}

    /**
     * An operator's endpoint that hands a code to their mail or SMS provider (ADR 0032).
     *
     * @param url where the code is POSTed as JSON; https, or http only to a loopback address
     * @param secret what the {@code Faye-Signature} HMAC is keyed with
     * @param perHour the most codes this tenant may send on the channel in an hour; 100 when unset
     */
    public record Webhook(
            String url, String secret, @Nullable Integer perHour) {}

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
