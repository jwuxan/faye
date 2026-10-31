package faye.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import faye.engine.Engine;
import faye.engine.EngineResponse;
import faye.engine.FlowException;
import faye.engine.FlowException.Code;
import faye.engine.FlowStore;
import faye.engine.HostKind;
import faye.engine.Redirect;
import faye.engine.ScreenView;
import faye.engine.Submission;
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

    record SubmitRequest(String nonce, String action, Map<String, Object> payload) {}

    record RedirectResponse(String redirectTo) {}

    record ErrorResponse(String error) {}

    /** The engine's screen, with the tenant's branding when it has any. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ScreenResponse(
            String flowId,
            String nonce,
            @Nullable String step,
            Map<String, Object> screen,
            TenantSettings.@Nullable Branding branding) {}

    private final Engine engine;
    private final FayeProperties props;
    private final FlowStore store;
    private final Tenants tenants;

    FlowController(Engine engine, FayeProperties props, FlowStore store, Tenants tenants) {
        this.engine = engine;
        this.props = props;
        this.store = store;
        this.tenants = tenants;
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
                yield props.loginUiUrl() + "/flow/" + screen.flowId();
            }
            case Redirect redirect -> redirect.url();
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

    /** A finished flow's answer also expires its cookie, so cookies do not pile up for the TTL. */
    @PostMapping("/api/flows/{flowId}/submit")
    ResponseEntity<Object> submit(
            @PathVariable String flowId, @RequestBody SubmitRequest body, HttpServletRequest http) {
        if (body.nonce() == null || !"submit".equals(body.action()) || body.payload() == null) {
            throw new FlowException(Code.INVALID_SUBMISSION);
        }
        EngineResponse response =
                engine.submit(flowId, binder(flowId, http), body.nonce(), new Submission(body.payload()));
        return switch (response) {
            case ScreenView screen -> ResponseEntity.ok(branded(screen));
            case Redirect redirect ->
                ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, binderCookie(flowId, "", Duration.ZERO))
                        .body(new RedirectResponse(redirect.url()));
        };
    }

    private ScreenResponse branded(ScreenView view) {
        TenantSettings.@Nullable Branding branding = store.load(view.flowId())
                .map(state -> tenants.settings(state.tenantId()).branding())
                .filter(b -> !b.isEmpty())
                .orElse(null);
        return new ScreenResponse(view.flowId(), view.nonce(), view.step(), view.screen(), branding);
    }

    @ExceptionHandler
    ResponseEntity<ErrorResponse> refused(FlowException e) {
        HttpStatus status = switch (e.code()) {
            case FLOW_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FLOW_FINISHED -> HttpStatus.GONE;
            case STALE_NONCE, CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_SUBMISSION -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(e.code().wireName()));
    }
}
