package faye.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import faye.engine.Engine;
import faye.engine.FlowException;
import faye.engine.FlowException.Code;
import faye.engine.HostFailure;
import faye.engine.Submission;
import faye.engine.spi.EngineResponse;
import faye.engine.spi.FlowStore;
import faye.engine.spi.Handoff;
import faye.engine.spi.HostKind;
import faye.engine.spi.QrGrants;
import faye.engine.spi.Redirect;
import faye.engine.spi.ScreenView;
import faye.handlers.QrSignIn;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The host's login and consent entry points and the screen protocol API used by the Login UI.
 *
 * <p>Starting a flow sets a cookie, {@code faye_flow_<flowId>}, holding the flow's binder; reading or
 * answering the flow needs it, and without it the flow does not exist (ADR 0024). One cookie per
 * flow lets several tabs sign in at once. The Login UI forwards the cookie from the browser.
 */
@RestController
class FlowController {

    /** The cookie that binds a flow to the browser that started it is this prefix and the flow id. */
    static final String COOKIE_PREFIX = "faye_flow_";

    private static final System.Logger LOG = System.getLogger(FlowController.class.getName());

    record SubmitRequest(String nonce, String action, Map<String, Object> payload) {}

    /** {@code failed} only when true: a failed sign in, which the Login UI shows at /error. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RedirectResponse(String redirectTo, @Nullable Boolean failed) {}

    /** A finished flow whose host hands its result to the page that embeds the Login UI (ADR 0025). */
    record HandoffResponse(Map<String, Object> result) {}

    /**
     * @param reference for {@code host_error} after a flow ended, the reference of its failure (ADR
     *     0027): something the person can hand support, which names the operator's log line and
     *     nothing about the flow
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorResponse(String error, @Nullable String reference) {
        ErrorResponse(String error) {
            this(error, null);
        }
    }

    /** The engine's screen, with the tenant's branding when it has any. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ScreenResponse(
            String flowId,
            String nonce,
            @Nullable String step,
            String path,
            Map<String, Object> screen,
            TenantSettings.@Nullable Branding branding) {}

    private final Engine engine;
    private final FayeProperties props;
    private final FlowStore store;
    private final Tenants tenants;
    private final QrGrants grants;

    FlowController(Engine engine, FayeProperties props, FlowStore store, Tenants tenants, QrGrants grants) {
        this.engine = engine;
        this.props = props;
        this.store = store;
        this.tenants = tenants;
        this.grants = grants;
    }

    /**
     * Starts the phone's side of a QR code sign in (ADR 0032), for the Login UI's {@code
     * /approve/{token}}: the binder cookie, and where the Login UI shows the first screen. A code
     * that is unknown, used or past its lifetime is answered as an unknown flow.
     */
    @PostMapping("/api/approve/{token}")
    ResponseEntity<Object> approve(@PathVariable String token, HttpServletRequest http) {
        String hash = QrSignIn.hash(token);
        boolean waiting = grants.find(hash)
                .filter(g -> g.status() == QrGrants.Status.PENDING && System.currentTimeMillis() < g.expiresAt())
                .isPresent();
        if (!waiting) throw new FlowException(Code.FLOW_NOT_FOUND);
        Map<String, Object> request = new HashMap<>();
        request.put("ip", http.getRemoteAddr());
        String userAgent = http.getHeader("User-Agent");
        if (userAgent != null) request.put("user_agent", userAgent);
        String binder = Engine.newBinder();
        return switch (engine.start(HostKind.APPROVE, hash, request, binder)) {
            case ScreenView screen ->
                ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, binderCookie(screen.flowId(), binder, props.flowTtl()))
                        .body(Map.of("location", screen.path() + "?flow=" + screen.flowId()));
            // A tenant with no approve entry, or one that ended at once: nothing to show on the phone
            case Redirect redirect -> throw new FlowException(Code.FLOW_NOT_FOUND);
            case Handoff handoff -> throw new FlowException(Code.FLOW_NOT_FOUND);
        };
    }

    @GetMapping("/login")
    ResponseEntity<Void> login(@RequestParam("login_challenge") String challenge, HttpServletRequest http) {
        return start(HostKind.LOGIN, challenge, http);
    }

    /** Runs the consent entry of the flow version the login ran. */
    @GetMapping("/consent")
    ResponseEntity<Void> consent(@RequestParam("consent_challenge") String challenge, HttpServletRequest http) {
        return start(HostKind.CONSENT, challenge, http);
    }

    /**
     * The Hydra host's routes: a redirect to the Login UI, or Hydra's redirect if the flow ended at
     * once. These are browser navigations, so a host that hands off has no business here: its result
     * (perhaps a one time code) would render as a page. An embedding host starts flows through its
     * own route or {@link Engine#start}. The refusal comes after the host completed the request
     * (the request is spent), so wiring such a host behind these routes is a misconfiguration.
     */
    private ResponseEntity<Void> start(HostKind kind, String challenge, HttpServletRequest http) {
        Map<String, Object> request = new HashMap<>();
        request.put("ip", http.getRemoteAddr());
        String userAgent = http.getHeader("User-Agent");
        if (userAgent != null) request.put("user_agent", userAgent);

        String binder = Engine.newBinder();
        var response = ResponseEntity.status(HttpStatus.FOUND);
        String location = switch (engine.start(kind, challenge, request, binder)) {
            case ScreenView screen -> {
                response.header(HttpHeaders.SET_COOKIE, binderCookie(screen.flowId(), binder, props.flowTtl()));
                // The Login UI shows each screen at its path; the flow id rides in the query (ADR 0029)
                yield props.loginUiUrl() + screen.path() + "?flow=" + screen.flowId();
            }
            // A sign in that failed before its first screen: the Login UI's /error, like one that failed later
            case Redirect redirect when redirect.failed() -> props.loginUiUrl() + "/error?reason=failed";
            case Redirect redirect -> checked(redirect.url());
            case Handoff handoff ->
                throw new IllegalStateException("a host that hands off cannot complete a redirecting route");
        };
        return response.location(URI.create(location)).build();
    }

    /**
     * HttpOnly and Lax, so scripts and cross site posts never carry it. Secure when the Login UI,
     * where the browser presents it, is served over https; host only, so the Login UI and these
     * endpoints must share a host name (ports may differ).
     */
    private String binderCookie(String flowId, String binder, Duration maxAge) {
        return ResponseCookie.from(COOKIE_PREFIX + flowId, binder)
                .httpOnly(true)
                .secure(props.loginUiUrl().startsWith("https://"))
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build()
                .toString();
    }

    /** The binder the browser sent for this flow; empty when it sent none, which no flow matches. */
    private static String binder(String flowId, HttpServletRequest http) {
        if (http.getCookies() == null) return "";
        for (Cookie c : http.getCookies()) {
            if (c.getName().equals(COOKIE_PREFIX + flowId)) return c.getValue();
        }
        return "";
    }

    @GetMapping("/api/flows/{flowId}")
    ScreenResponse view(@PathVariable String flowId, HttpServletRequest http) {
        return branded(engine.view(flowId, binder(flowId, http)));
    }

    /**
     * A finished flow's answer also expires its cookie, so cookies do not pile up for the TTL. An
     * action other than {@code submit} is a screen action (ADR 0032): its issuer answers and the same
     * screen comes back.
     */
    @PostMapping("/api/flows/{flowId}/submit")
    ResponseEntity<Object> submit(
            @PathVariable String flowId, @RequestBody SubmitRequest body, HttpServletRequest http) {
        if (body.nonce() == null || body.action() == null || body.payload() == null) {
            throw new FlowException(Code.INVALID_SUBMISSION);
        }
        if (!"submit".equals(body.action())) {
            if (body.payload().values().stream().anyMatch(java.util.Objects::isNull)) {
                throw new FlowException(Code.UNKNOWN_ACTION);
            }
            ScreenView acted = engine.act(flowId, binder(flowId, http), body.nonce(), body.action(), body.payload());
            return ResponseEntity.ok(branded(acted));
        }
        EngineResponse response =
                engine.submit(flowId, binder(flowId, http), body.nonce(), new Submission(body.payload()));
        return switch (response) {
            case ScreenView screen -> ResponseEntity.ok(branded(screen));
            case Redirect redirect ->
                ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, binderCookie(flowId, "", Duration.ZERO))
                        .body(new RedirectResponse(checked(redirect.url()), redirect.failed() ? true : null));
            case Handoff handoff ->
                ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, binderCookie(flowId, "", Duration.ZERO))
                        .body(new HandoffResponse(handoff.result()));
        };
    }

    private ScreenResponse branded(ScreenView view) {
        TenantSettings.@Nullable Branding branding = store.load(view.flowId())
                .map(state -> tenants.settings(state.tenantId()).branding())
                .filter(b -> !b.isEmpty())
                .orElse(null);
        return new ScreenResponse(view.flowId(), view.nonce(), view.step(), view.path(), view.screen(), branding);
    }

    /**
     * A redirect the host asked for, if it is one a browser can safely follow: an absolute http(s)
     * URL with a host and nothing a header or {@code window.location} would read another way.
     * The Login UI navigates to it, so a {@code javascript:} URL would run in its origin (ADR 0007,
     * seed finding 3). Anything else is the host's fault and is refused as such.
     */
    static String checked(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (uri.isAbsolute()
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && url.chars().noneMatch(c -> c < 0x21 || c == 0x7f)) {
                return url;
            }
        } catch (java.net.URISyntaxException e) {
            // refused below
        }
        throw new BadRedirect();
    }

    /** The host answered with a redirect {@link #checked} refuses. */
    static final class BadRedirect extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRedirect() {
            super("the authorization server answered with a redirect a browser must not follow");
        }
    }

    @ExceptionHandler
    ResponseEntity<ErrorResponse> badRedirect(BadRedirect e) {
        LOG.log(System.Logger.Level.ERROR, e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("host_error"));
    }

    /** The host threw as it was told how the flow ended; the engine has logged where (FailureLog). */
    @ExceptionHandler
    ResponseEntity<ErrorResponse> hostFailed(HostFailure e) {
        LOG.log(System.Logger.Level.ERROR, e.getMessage(), e.getCause());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("host_error", e.site().reference()));
    }

    @ExceptionHandler
    ResponseEntity<ErrorResponse> refused(FlowException e) {
        HttpStatus status = switch (e.code()) {
            case FLOW_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FLOW_FINISHED, FLOW_EXPIRED -> HttpStatus.GONE;
            case STALE_NONCE, CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_SUBMISSION, UNKNOWN_ACTION -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(e.code().wireName()));
    }
}
