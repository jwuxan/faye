package faye.engine.spi;

/** Issues TOTP enrollment material (RFC 6238). */
public interface OtpIssuer {

    /**
     * A fresh secret and the provisioning URI an authenticator app reads.
     *
     * @param subject the account enrolling
     * @param accountName the label the authenticator shows for the account
     * @param issuerName the label the authenticator shows for the service
     */
    OtpEnrollment issue(String subject, String accountName, String issuerName);
}
