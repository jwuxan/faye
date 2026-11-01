package faye.engine.spi;

/**
 * TOTP enrollment material.
 *
 * @param secret the shared secret, base32
 * @param uri the provisioning URI the Login UI shows as a QR
 */
public record OtpEnrollment(String secret, String uri) {}
