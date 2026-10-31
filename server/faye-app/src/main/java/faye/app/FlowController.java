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
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Hydra's login and consent entry points and the screen protocol API used by the Login UI. */
@RestController
class FlowController {

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

    /** Runs the consent section of the flow version the login ran. */
    @GetMapping("/consent")
    ResponseEntity<Void> consent(@RequestParam("consent_challenge") String challenge, HttpServletRequest http) {
        return start(HostKind.CONSENT, challenge, http);
    }

    private ResponseEntity<Void> start(HostKind kind, String challenge, HttpServletRequest http) {
        Map<String, Object> request = new HashMap<>();
        request.put("ip", http.getRemoteAddr());
        String userAgent = http.getHeader("User-Agent");
        if (userAgent != null) request.put("user_agent", userAgent);

        String location = switch (engine.start(kind, challenge, request)) {
            case ScreenView screen -> props.loginUiUrl() + "/flow/" + screen.flowId();
            case Redirect redirect -> redirect.url();
        };
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }

    @GetMapping("/api/flows/{flowId}")
    ScreenResponse view(@PathVariable String flowId) {
        return branded(engine.view(flowId));
    }

    @PostMapping("/api/flows/{flowId}/submit")
    Object submit(@PathVariable String flowId, @RequestBody SubmitRequest body) {
        if (body.nonce() == null || !"submit".equals(body.action()) || body.payload() == null) {
            throw new FlowException(Code.INVALID_SUBMISSION);
        }
        EngineResponse response = engine.submit(flowId, body.nonce(), new Submission(body.payload()));
        return switch (response) {
            case ScreenView screen -> branded(screen);
            case Redirect redirect -> new RedirectResponse(redirect.url());
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
