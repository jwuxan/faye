package faye.app;

import faye.engine.spi.Authenticated;
import faye.engine.spi.Completion;
import faye.engine.spi.Consented;
import faye.engine.spi.FlowHost;
import faye.engine.spi.Handoff;
import faye.engine.spi.HostKind;
import faye.engine.spi.HostParams;
import faye.engine.spi.HostRequest;
import faye.engine.spi.QrGrants;
import java.util.Map;

/**
 * The host of the phone's side of QR code sign in (ADR 0032), in front of the app's own host: an
 * {@link HostKind#APPROVE} request is a grant, by its token's hash, and runs for the waiting
 * device's tenant, client and preview flag, never anything the phone sends. It completes with a
 * {@link Handoff}: nothing is redirected and no session is created on the phone. Every other kind
 * goes to the wrapped host.
 */
final class QrApprovalHost implements FlowHost {

    private final FlowHost host;
    private final QrGrants grants;

    QrApprovalHost(FlowHost host, QrGrants grants) {
        this.host = host;
        this.grants = grants;
    }

    @Override
    public HostRequest load(HostKind kind, String ref) {
        if (kind != HostKind.APPROVE) return host.load(kind, ref);
        QrGrants.Grant grant = grants.find(ref).orElseThrow(() -> new IllegalArgumentException("no such QR code"));
        return new HostRequest(HostKind.APPROVE, ref, grant.tenant(), grant.preview(), grant.client(), HostParams.NONE);
    }

    @Override
    public Completion accept(HostRequest request, Authenticated outcome) {
        if (request.kind() != HostKind.APPROVE) return host.accept(request, outcome);
        return new Handoff(Map.of("qr", "approved"));
    }

    @Override
    public Completion accept(HostRequest request, Consented outcome) {
        return host.accept(request, outcome);
    }

    @Override
    public Completion reject(HostRequest request, String error, String description) {
        if (request.kind() != HostKind.APPROVE) return host.reject(request, error, description);
        return new Handoff(Map.of("qr", "refused", "reason", description));
    }
}
