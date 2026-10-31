package faye.host.mock;

import faye.engine.Authenticated;
import faye.engine.Consented;
import faye.engine.FlowHost;
import faye.engine.HostKind;
import faye.engine.HostRequest;
import faye.engine.Redirect;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/** In memory authorization server for tests and the conformance suite. Records every decision. */
public final class MockFlowHost implements FlowHost {

    public record Accepted(HostRequest request, Authenticated outcome) {}

    public record ConsentAccepted(HostRequest request, Consented outcome) {}

    public record Rejected(HostRequest request, String error, String description) {}

    private final Map<String, HostRequest> requests = new ConcurrentHashMap<>();
    private final List<Accepted> accepted = new CopyOnWriteArrayList<>();
    private final List<ConsentAccepted> consented = new CopyOnWriteArrayList<>();
    private final List<Rejected> rejected = new CopyOnWriteArrayList<>();
    private final String callbackUrl;
    private final Map<String, Object> client;

    /**
     * @param callbackUrl where accept and reject redirect, with the challenge and result appended
     * @param tenant the tenant of client {@code demo}; none when null, so its flows run the default tenant's
     */
    public MockFlowHost(String callbackUrl, @Nullable String tenant) {
        this.callbackUrl = callbackUrl;
        this.client = tenant == null
                ? Map.of("client_id", "demo")
                : Map.of("client_id", "demo", "metadata", Map.of("tenant", tenant));
    }

    /** @param callbackUrl where accept and reject redirect, with the challenge and result appended */
    public MockFlowHost(String callbackUrl) {
        this(callbackUrl, null);
    }

    public MockFlowHost() {
        this("mock://callback");
    }

    /** Registers a challenge. Unregistered challenges load as a plain request from client {@code demo}. */
    public void register(HostRequest request) {
        requests.put(request.challenge(), request);
    }

    @Override
    public HostRequest load(HostKind kind, String challenge) {
        HostRequest req = requests.get(challenge);
        if (req != null && req.kind() == kind) return req;
        return new HostRequest(kind, challenge, client, Map.of());
    }

    @Override
    public Redirect accept(HostRequest request, Authenticated outcome) {
        accepted.add(new Accepted(request, outcome));
        return new Redirect(callbackUrl + "?challenge=" + request.challenge() + "&result=accepted");
    }

    @Override
    public Redirect accept(HostRequest request, Consented outcome) {
        consented.add(new ConsentAccepted(request, outcome));
        return new Redirect(callbackUrl + "?challenge=" + request.challenge() + "&result=accepted");
    }

    @Override
    public Redirect reject(HostRequest request, String error, String description) {
        rejected.add(new Rejected(request, error, description));
        return new Redirect(callbackUrl + "?challenge=" + request.challenge() + "&result=" + error);
    }

    public List<Accepted> accepted() {
        return List.copyOf(accepted);
    }

    public List<ConsentAccepted> consented() {
        return List.copyOf(consented);
    }

    public List<Rejected> rejected() {
        return List.copyOf(rejected);
    }
}
