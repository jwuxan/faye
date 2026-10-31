package faye.app;

import faye.check.FlowPath;
import faye.check.Publisher;
import faye.engine.Base32;
import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.engine.TraceEvent;
import faye.identity.IdentityHandlers;
import faye.identity.Passwords;
import faye.identity.User;
import faye.identity.UserStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The preview loop: scenarios and preview only test accounts for a tenant's next preview runs, the
 * live trace feed, and what a preview run did against what the checker predicted. The {@code
 * /admin} endpoints need the admin token and exist only while previews are on; {@code
 * /api/preview/flows/{flowId}} is for the demo client, with the unguessable flow id as the
 * capability.
 */
@RestController
class PreviewController {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** @param scenario what the tenant's next preview runs see in {@code request.*} */
    record PreviewView(boolean enabled, PreviewScenario scenario) {}

    record SeedRequest(boolean totp) {}

    /**
     * A preview only account; nothing but preview clients can sign in with it.
     *
     * @param totpSecret base32 TOTP secret already enrolled, or null
     */
    record Seeded(
            String identifier, String password, @Nullable String totpSecret) {}

    /**
     * How a preview run ended.
     *
     * @param failure the reason a run that did not succeed failed
     * @param amr what the engine computed for a login that succeeded
     */
    record Result(
            boolean succeeded,
            @Nullable String failure,
            List<String> amr,
            @Nullable String acr) {}

    /**
     * What a preview run did, and what the checker predicted for the way it went.
     *
     * @param draft the version run is not one the tenant published
     * @param visits the steps and options the run went through, keyed as {@link
     *     AdminController.PathView#visits()}
     * @param result null while the run is waiting on a screen
     * @param expected the enumerated paths that make the same decisions and calls as the run
     * @param matches null while the run is under way; else whether a predicted path ends the way the
     *     run did: succeeding with the same amr and acr, or failing
     */
    record PreviewRun(
            String flowId,
            String tenant,
            String section,
            String versionHash,
            boolean draft,
            List<TraceEvent> events,
            List<String> visits,
            @Nullable Result result,
            List<AdminController.PathView> expected,
            @Nullable Boolean matches) {}

    record Refusal(String error) {}

    private final AdminAuth auth;
    private final DraftPreviews previews;
    private final TenantConfigStore store;
    private final TraceStore traces;
    private final PreviewFeed feed;
    private final FlowVersions versions;
    private final Publisher publisher;
    private final UserStore users;

    PreviewController(
            AdminAuth auth,
            DraftPreviews previews,
            TenantConfigStore store,
            TraceStore traces,
            PreviewFeed feed,
            FlowVersions versions,
            Publisher publisher,
            UserStore users) {
        this.auth = auth;
        this.previews = previews;
        this.store = store;
        this.traces = traces;
        this.feed = feed;
        this.versions = versions;
        this.publisher = publisher;
        this.users = users;
    }

    /** Whether previews are on, and the tenant's scenario; the one admin preview call that works with them off. */
    @GetMapping("/admin/tenants/{tenant}/preview")
    PreviewView preview(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization) {
        auth.check(authorization, tenant);
        return new PreviewView(previews.enabled(), store.scenario(tenant).orElse(PreviewScenario.NONE));
    }

    @PutMapping("/admin/tenants/{tenant}/preview/scenario")
    ResponseEntity<?> saveScenario(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization,
            @RequestBody PreviewScenario body) {
        check(authorization, tenant);
        String problem = body.problem();
        if (problem != null) return ResponseEntity.badRequest().body(new Refusal(problem));
        store.saveScenario(tenant, body);
        return ResponseEntity.ok(body);
    }

    /** Seeds a fresh preview only account in the tenant's user pool, with TOTP enrolled if asked. */
    @PostMapping("/admin/tenants/{tenant}/preview/users")
    ResponseEntity<Seeded> seed(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization,
            @RequestBody SeedRequest body) {
        check(authorization, tenant);
        String id = random(9).toLowerCase(Locale.ROOT).replace('_', 'x').replace('-', 'x');
        String identifier = IdentityHandlers.normalize("tester-" + id + "@preview.faye.test");
        String password = random(15);
        @Nullable String totpSecret = body.totp() ? Base32.encode(bytes(20)) : null;
        users.save(new User(
                "preview-" + id,
                identifier,
                "Preview tester",
                true,
                Passwords.hash(password),
                totpSecret,
                -1,
                List.of(),
                false,
                true,
                tenant));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Seeded(identifier, password, totpSecret));
    }

    /** The tenant's preview trace events from now on (or after {@code Last-Event-ID}), as they happen. */
    @GetMapping(path = "/admin/tenants/{tenant}/preview/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization,
            @RequestHeader(name = "Last-Event-ID", required = false) @Nullable String lastEventId) {
        check(authorization, tenant);
        return feed.follow(tenant, lastEventId);
    }

    @GetMapping("/admin/tenants/{tenant}/preview/flows/{flowId}")
    PreviewRun adminRun(
            @PathVariable String tenant,
            @PathVariable String flowId,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization) {
        check(authorization, tenant);
        PreviewRun run = run(flowId);
        if (!run.tenant().equals(tenant)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return run;
    }

    @GetMapping("/api/preview/flows/{flowId}")
    PreviewRun run(@PathVariable String flowId) {
        if (!previews.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<TraceStore.Recorded> recorded = traces.flow(flowId);
        if (recorded.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String tenant = recorded.get(0).tenantId();
        List<TraceEvent> events =
                recorded.stream().map(TraceStore.Recorded::event).toList();
        TraceEvent start = events.get(0);
        String hash = Objects.requireNonNullElse(start.id(), "");
        String section = Objects.requireNonNullElse(start.outcome(), "login");
        TraceEvent last = events.getLast();
        @Nullable
        Result result = "end".equals(last.event())
                ? new Result(
                        "succeed".equals(last.outcome()),
                        "succeed".equals(last.outcome()) ? null : last.outcome(),
                        Objects.requireNonNullElse(last.amr(), List.of()),
                        last.acr())
                : null;

        List<String> signature = signature(events);
        Optional<FlowVersion> version = versions.get(hash);
        List<AdminController.PathView> expected = version.isEmpty()
                ? List.of()
                : publisher.check(tenant, version.get().flow()).paths().stream()
                        .filter(p -> p.section().equals(section) && signature(p).equals(signature))
                        .map(AdminController.PathView::of)
                        .toList();
        @Nullable
        // A run refused at succeed (acr_values, MFA policy) fails where its path predicted success:
        // a mismatch worth showing
        Boolean matches = result == null
                ? null
                : expected.stream()
                        .anyMatch(p -> result.succeeded()
                                ? p.succeeds()
                                        && new HashSet<>(p.amr()).equals(new HashSet<>(result.amr()))
                                        && Objects.equals(p.acr(), result.acr())
                                : !p.succeeds());
        return new PreviewRun(
                flowId,
                tenant,
                section,
                hash,
                !versions.history(tenant).contains(hash),
                events,
                visits(events),
                result,
                expected,
                matches);
    }

    private void check(@Nullable String authorization, String tenant) {
        auth.check(authorization, tenant);
        if (!previews.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "previews are off");
    }

    /**
     * What a path and a run have to agree on: the steps entered and options chosen, each call with
     * what it proved or that it failed, and each hook that failed, in order. Screens, retries and
     * hooks that succeed leave no mark of their own: what they lead to does.
     */
    static List<String> signature(FlowPath path) {
        List<String> out = new ArrayList<>();
        for (FlowPath.Event e : path.events()) {
            switch (e) {
                case FlowPath.Entered s -> out.add("step " + s.step());
                case FlowPath.Chose c -> out.add("option " + c.option());
                case FlowPath.Called c ->
                    out.add("call " + c.handler() + " " + (c.ok() ? String.join("+", c.amr()) : "failed"));
                case FlowPath.Hooked h -> {
                    if (!h.ok()) out.add("hook " + h.hook() + " failed");
                }
                case FlowPath.Assumed a -> {}
            }
        }
        return out;
    }

    static List<String> signature(List<TraceEvent> events) {
        List<String> out = new ArrayList<>();
        for (TraceEvent e : events) {
            boolean ok = "ok".equals(e.outcome());
            switch (e.event()) {
                case "step" -> out.add("step " + e.id());
                case "option" -> out.add("option " + e.id());
                case "call" ->
                    out.add("call " + e.id() + " "
                            + (ok ? String.join("+", Objects.requireNonNullElse(e.amr(), List.of())) : "failed"));
                case "hook" -> {
                    if (!ok) out.add("hook " + e.id() + " failed");
                }
                default -> {}
            }
        }
        return out;
    }

    /** The visit keys of the steps and options a run went through; see {@link AdminController.PathView}. */
    static List<String> visits(List<TraceEvent> events) {
        List<String> out = new ArrayList<>();
        String last = "";
        for (TraceEvent e : events) {
            String key = switch (e.event()) {
                case "step" -> e.id();
                case "option" -> last + "/" + e.id();
                default -> null;
            };
            if (key != null) {
                out.add(key);
                last = key;
            }
        }
        return out;
    }

    private static String random(int bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(bytes));
    }

    private static byte[] bytes(int n) {
        byte[] out = new byte[n];
        RANDOM.nextBytes(out);
        return out;
    }
}
