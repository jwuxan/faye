package faye.app;

import faye.engine.FailureSite;
import faye.engine.Failures;
import faye.engine.FlowState;
import faye.ir.NodePaths;
import java.util.Set;

/**
 * The operator's view of a run that ended abnormally (ADR 0027): one log line with the tenant, the
 * flow, the version, where in the YAML it ended and the reference the person signing in may quote.
 * A failure the person caused (a wrong password or code, a consent they denied, a passkey of another
 * account) is logged at {@code INFO}; anything else at {@code WARNING}, since it points at the flow,
 * a hook, a handler or the host.
 */
final class FailureLog implements Failures {

    /** Codes of failures the person signing in caused. */
    static final Set<String> PERSON = Set.of("invalid_credentials", "invalid_code", "wrong_account", "consent_denied");

    private static final System.Logger LOG = System.getLogger(FailureLog.class.getName());

    private final SourceLines lines;

    FailureLog(SourceLines lines) {
        this.lines = lines;
    }

    @Override
    public void failed(FlowState state, FailureSite site) {
        LOG.log(level(site.code()), line(state, site));
    }

    static System.Logger.Level level(String code) {
        return PERSON.contains(code) ? System.Logger.Level.INFO : System.Logger.Level.WARNING;
    }

    /**
     * {@code flow failed: code=hook_error reference=ref_... source=acme.yaml:21:7 kind=hook
     * tenant=acme flow=f_... version=sha256:... entry=login step=risk path=1.0 preview=false}.
     * Values are ids, codes and positions only, never what the person typed.
     */
    String line(FlowState state, FailureSite site) {
        String source = lines.locate(state.versionHash(), site.site())
                .map(l -> l + " kind=" + l.kind())
                .orElse("none");
        return "flow failed: code=" + site.code()
                + " reference=" + site.reference()
                + " source=" + source
                + " tenant=" + state.tenantId()
                + " flow=" + state.flowId()
                + " version=" + state.versionHash()
                + " entry=" + site.entry()
                + " step=" + (site.step() == null ? "none" : site.step())
                + " path=" + NodePaths.key(site.path())
                + " preview=" + state.preview();
    }
}
