package faye.engine.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The grants of QR code sign in (ADR 0032): a device waiting on a QR code creates one, the phone's
 * approve flow approves it, and the waiting flow consumes it once. Only the token's hash is stored;
 * the token itself lives in the waiting screen's data and in the QR code.
 */
public interface QrGrants {

    /** Where a grant is. */
    enum Status {
        PENDING,
        APPROVED,
        REFUSED,
        CONSUMED
    }

    /**
     * One grant.
     *
     * @param tokenHash base64url SHA-256 of the token the QR code carries
     * @param flowId the waiting flow; no other flow may consume it
     * @param client what the phone shows of the client the waiting device signs in to ({@code
     *     client_id}, {@code client_name})
     * @param number the two digit number the waiting device shows, which the person picks on the phone
     * @param device what the phone shows of the waiting device (its browser), or empty
     * @param expiresAt epoch milliseconds
     * @param subject who approved; null until approved
     * @param amr what the phone's approve flow proved; empty until approved
     */
    record Grant(
            String tokenHash,
            String flowId,
            String tenant,
            boolean preview,
            Map<String, Object> client,
            int number,
            String device,
            long expiresAt,
            Status status,
            @Nullable String subject,
            List<String> amr) {
        public Grant {
            client = Map.copyOf(client);
            amr = List.copyOf(amr);
        }
    }

    void create(Grant grant);

    Optional<Grant> find(String tokenHash);

    /**
     * Write effect: approves a pending grant that has not expired at {@code now}, for {@code
     * subject} with what the approve flow proved. Idempotent for the same subject.
     *
     * @return false when the grant is gone, expired, refused or approved for someone else
     */
    boolean approve(String tokenHash, String subject, List<String> amr, long now);

    /** Refuses a pending grant (the wrong number was picked), so the waiting device stops at once. */
    void refuse(String tokenHash);

    /**
     * Guard: consumes an approved grant of {@code flowId} that has not expired at {@code now},
     * atomically and once; the same flow consuming it again gets it again (a double submit).
     *
     * @return the consumed grant, or empty
     */
    Optional<Grant> consume(String tokenHash, String flowId, long now);
}
