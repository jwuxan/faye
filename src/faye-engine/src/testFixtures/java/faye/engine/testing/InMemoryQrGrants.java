package faye.engine.testing;

import faye.engine.spi.QrGrants;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** QR code grants in memory, for tests and for hosts that embed without a database (ADR 0032). */
public final class InMemoryQrGrants implements QrGrants {

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    @Override
    public void create(Grant grant) {
        grants.put(grant.tokenHash(), grant);
    }

    @Override
    public Optional<Grant> find(String tokenHash) {
        return Optional.ofNullable(grants.get(tokenHash));
    }

    @Override
    public synchronized boolean approve(String tokenHash, String subject, List<String> amr, long now) {
        Grant g = grants.get(tokenHash);
        if (g == null) return false;
        if (g.status() == Status.APPROVED) return subject.equals(g.subject());
        if (g.status() != Status.PENDING || now >= g.expiresAt()) return false;
        grants.put(tokenHash, with(g, Status.APPROVED, subject, amr));
        return true;
    }

    @Override
    public synchronized void refuse(String tokenHash) {
        Grant g = grants.get(tokenHash);
        if (g != null && g.status() == Status.PENDING) grants.put(tokenHash, with(g, Status.REFUSED, null, List.of()));
    }

    @Override
    public synchronized Optional<Grant> consume(String tokenHash, String flowId, long now) {
        Grant g = grants.get(tokenHash);
        if (g == null || !g.flowId().equals(flowId) || now >= g.expiresAt()) return Optional.empty();
        if (g.status() == Status.CONSUMED) return Optional.of(g);
        if (g.status() != Status.APPROVED) return Optional.empty();
        Grant consumed = with(g, Status.CONSUMED, Objects.requireNonNull(g.subject()), g.amr());
        grants.put(tokenHash, consumed);
        return Optional.of(consumed);
    }

    private static Grant with(
            Grant g, Status status, @org.jspecify.annotations.Nullable String subject, List<String> amr) {
        return new Grant(
                g.tokenHash(),
                g.flowId(),
                g.tenant(),
                g.preview(),
                g.client(),
                g.number(),
                g.device(),
                g.expiresAt(),
                status,
                subject,
                amr);
    }
}
