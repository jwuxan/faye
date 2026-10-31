package faye.app;

import faye.check.CheckFailed;
import faye.check.Finding;
import faye.check.FlowPath;
import faye.check.Publisher;
import faye.check.Report;
import faye.dsl.DslException;
import faye.dsl.FlowCompiler;
import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.engine.Handler;
import faye.engine.HandlerRegistry;
import faye.engine.HookException;
import faye.engine.HookOutput;
import faye.engine.ScreenType;
import faye.hooks.SandboxedHooks;
import faye.ir.Flow;
import faye.ir.HookDef;
import faye.ir.IrCodec;
import faye.ir.SourceRef;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The admin API behind the Admin UI: drafts, checks, publishing and rollback of a configured
 * tenant's flow, hook test runs, and tenant settings. Every request carries {@code Authorization:
 * Bearer <faye.admin-token>}; with no token configured the API does not exist. The request body of
 * validate and publish is the flow's YAML. A hook's {@code file:} is read from next to the tenant's
 * configured flow.
 */
@RestController
class AdminController {

    /** Larger drafts are refused; the shipped flows are under 3 KiB. */
    static final int MAX_DRAFT_BYTES = 256 * 1024;

    /** Options a flow may give each screen type it can show; see {@link ScreenType}. */
    static final Map<String, List<String>> SCREEN_OPTIONS = Map.of(
            "identifier", List.of("passkey"),
            "passkey", List.of("register"),
            "error", List.of("code"));

    /** @param source {@code file:line:column}, or empty for a version read back from the store */
    record Problem(String source, int line, int column, String message) {
        static Problem of(SourceRef at, String message) {
            return new Problem(at.toString(), at.line(), at.column(), message);
        }
    }

    /**
     * @param visits the steps and options the path goes through, in order: a step as its id, an
     *     option as the step or option it belongs to, a slash, and its id ({@code credential/password})
     */
    record PathView(
            String section,
            String path,
            boolean succeeds,
            List<String> amr,
            @Nullable String acr,
            List<String> visits) {
        static PathView of(FlowPath p) {
            List<String> visits = new ArrayList<>();
            String last = "";
            for (FlowPath.Event e : p.events()) {
                String key = switch (e) {
                    case FlowPath.Entered s -> s.step();
                    case FlowPath.Chose c -> last + "/" + c.option();
                    default -> null;
                };
                if (key != null) {
                    visits.add(key);
                    last = key;
                }
            }
            return new PathView(p.section(), p.describe(), p.succeeded(), p.amr(), p.acr(), visits);
        }
    }

    record Validation(boolean ok, List<Problem> errors, List<Problem> warnings, String summary, List<PathView> paths) {
        static Validation of(Report report) {
            return new Validation(
                    report.ok(),
                    problems(report.errors()),
                    problems(report.warnings()),
                    report.summary(),
                    report.paths().stream().map(PathView::of).toList());
        }

        static Validation of(DslException e) {
            List<Problem> errors = e.diagnostics().stream()
                    .map(d -> Problem.of(d.source(), d.message()))
                    .toList();
            return new Validation(false, errors, List.of(), "", List.of());
        }

        private static List<Problem> problems(List<Finding> findings) {
            return findings.stream()
                    .map(f -> Problem.of(f.source(), f.message()))
                    .toList();
        }
    }

    record Published(String versionHash, String summary, List<Problem> warnings) {}

    record Versions(@Nullable String current, List<String> versions) {}

    record Current(@Nullable String versionHash) {}

    record TenantView(String id, @Nullable String current) {}

    record HandlerView(String name, List<String> amr, boolean bindsSubject) {}

    record ScreenOptions(String type, List<String> options) {}

    /** @param egress named HTTP targets the server can reach; a tenant's hooks get those it grants */
    record Catalog(List<HandlerView> handlers, List<ScreenOptions> screens, List<String> egress) {}

    /**
     * @param origin where {@code yaml} came from: the saved {@code draft}, the {@code current}
     *     version, or the tenant's {@code config}ured flow
     * @param files hook files the YAML names, by file name, read from next to the configured flow
     */
    record Draft(String yaml, String origin, Map<String, String> files) {}

    record DraftBody(@Nullable String yaml) {}

    /** @param yaml empty for a version published before sources were kept */
    record VersionView(String hash, String yaml, String ir, boolean current) {}

    /**
     * @param ctx what the hook sees as {@code ctx}
     * @param options for a hook that picks: the enabled option ids, also passed as {@code
     *     ctx.options}; null for a hook in a step
     */
    record HookTest(
            @Nullable String source,
            @Nullable List<String> http,
            @Nullable Integer timeoutMs,
            @Nullable Map<String, Object> ctx,
            @Nullable List<String> options) {}

    /**
     * @param result what the hook returned, when it returned
     * @param error why it did not return: a throw, a timeout, the memory cap, a refused egress call
     * @param accepted the engine would act on the result, as {@code effect} says; if false, the step
     *     would fail with {@code hook_error}
     */
    record HookRun(
            @Nullable Object result,
            @Nullable String error,
            boolean accepted,
            String effect,
            List<String> logs,
            long millis) {}

    /**
     * @param settings in effect: saved ones, else {@code configured}
     * @param saved settings were saved through this API, overriding config
     * @param egress targets the tenant's hooks may call; operator config, not editable here
     */
    record SettingsView(TenantSettings settings, TenantSettings configured, boolean saved, List<String> egress) {}

    record Refusal(String error) {}

    private final Publisher publisher;
    private final FlowVersions versions;
    private final FayeProperties props;
    private final TenantConfigStore store;
    private final Tenants tenants;
    private final HandlerRegistry handlers;
    private final SandboxedHooks hooks;
    private final AdminAuth auth;

    AdminController(
            Publisher publisher,
            FlowVersions versions,
            FayeProperties props,
            TenantConfigStore store,
            Tenants tenants,
            HandlerRegistry handlers,
            SandboxedHooks hooks,
            AdminAuth auth) {
        this.publisher = publisher;
        this.versions = versions;
        this.props = props;
        this.store = store;
        this.tenants = tenants;
        this.handlers = handlers;
        this.hooks = hooks;
        this.auth = auth;
    }

    @GetMapping("/admin/catalog")
    Catalog catalog(@RequestHeader(name = "Authorization", required = false) @Nullable String authorization) {
        authorize(authorization);
        List<HandlerView> handlerViews = handlers.all().stream()
                .sorted(Comparator.comparing(Handler::name))
                .map(h -> new HandlerView(h.name(), h.amr().stream().sorted().toList(), h.bindsSubject()))
                .toList();
        // The method picker belongs to a choose; a flow cannot show it on its own
        List<ScreenOptions> screens = Stream.of(ScreenType.values())
                .filter(t -> t != ScreenType.METHOD_PICKER)
                .map(t -> new ScreenOptions(t.wireName(), SCREEN_OPTIONS.getOrDefault(t.wireName(), List.of())))
                .toList();
        List<String> egress = props.egress().keySet().stream().sorted().toList();
        return new Catalog(handlerViews, screens, egress);
    }

    @GetMapping("/admin/tenants")
    List<TenantView> tenants(@RequestHeader(name = "Authorization", required = false) @Nullable String authorization) {
        authorize(authorization);
        return tenants.ids().stream()
                .sorted()
                .map(id -> new TenantView(
                        id, versions.current(id).map(FlowVersion::hash).orElse(null)))
                .toList();
    }

    @GetMapping("/admin/tenants/{tenant}/draft")
    Draft draft(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization) {
        authorize(authorization, tenant);
        String origin = "draft";
        Optional<String> yaml = store.draft(tenant);
        if (yaml.isEmpty()) {
            origin = "current";
            yaml = versions.current(tenant).flatMap(v -> store.source(v.hash()));
        }
        if (yaml.isEmpty()) {
            origin = "config";
            yaml = Optional.of(EngineConfiguration.read(configured(tenant)));
        }
        return new Draft(yaml.get(), origin, files(tenant, yaml.get()));
    }

    @PutMapping("/admin/tenants/{tenant}/draft")
    Draft saveDraft(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization,
            @RequestBody DraftBody body) {
        authorize(authorization, tenant);
        String yaml = body.yaml();
        if (yaml == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing yaml");
        if (yaml.getBytes(StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        store.saveDraft(tenant, yaml);
        return new Draft(yaml, "draft", files(tenant, yaml));
    }

    @PostMapping("/admin/tenants/{tenant}/validate")
    ResponseEntity<Validation> validate(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization,
            @RequestBody String yaml) {
        authorize(authorization, tenant);
        try {
            FlowCompiler.Compiled compiled = compile(tenant, yaml);
            return ResponseEntity.ok(Validation.of(publisher.check(tenant, compiled.flow(), compiled.hooks())));
        } catch (DslException e) {
            return ResponseEntity.unprocessableEntity().body(Validation.of(e));
        }
    }

    @PostMapping("/admin/tenants/{tenant}/publish")
    ResponseEntity<?> publish(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization,
            @RequestBody String yaml) {
        authorize(authorization, tenant);
        try {
            FlowCompiler.Compiled compiled = compile(tenant, yaml);
            Flow flow = compiled.flow();
            FlowVersion version = publisher.publish(tenant, flow, compiled.hooks());
            store.saveSource(version.hash(), yaml);
            Report report = publisher.check(tenant, flow);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new Published(
                            version.hash(),
                            report.summary(),
                            Validation.of(report).warnings()));
        } catch (DslException e) {
            return ResponseEntity.unprocessableEntity().body(Validation.of(e));
        } catch (CheckFailed e) {
            return ResponseEntity.unprocessableEntity().body(Validation.of(e.report()));
        }
    }

    @GetMapping("/admin/tenants/{tenant}/versions")
    Versions versions(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization) {
        authorize(authorization, tenant);
        return new Versions(versions.current(tenant).map(FlowVersion::hash).orElse(null), versions.history(tenant));
    }

    @GetMapping("/admin/tenants/{tenant}/versions/{hash}")
    VersionView version(
            @PathVariable String tenant,
            @PathVariable String hash,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization) {
        authorize(authorization, tenant);
        if (!versions.history(tenant).contains(hash)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        FlowVersion version = versions.get(hash).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean current =
                versions.current(tenant).map(v -> v.hash().equals(hash)).orElse(false);
        return new VersionView(hash, store.source(hash).orElse(""), IrCodec.canonicalJson(version.flow()), current);
    }

    /** Rolls back (or forward) to a version the tenant published before. */
    @PostMapping("/admin/tenants/{tenant}/current")
    ResponseEntity<?> current(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization,
            @RequestBody Current body) {
        authorize(authorization, tenant);
        if (body.versionHash() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            return ResponseEntity.ok(
                    new Current(publisher.rollback(tenant, body.versionHash()).hash()));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (CheckFailed e) {
            return ResponseEntity.unprocessableEntity().body(Validation.of(e.report()));
        }
    }

    /**
     * Runs a hook definition once, as the tenant's published hooks run: same sandbox, same limits,
     * only the egress targets it declares and the tenant grants. Nothing is stored.
     */
    @PostMapping("/admin/tenants/{tenant}/hooks/test")
    ResponseEntity<?> testHook(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization,
            @RequestBody HookTest body) {
        authorize(authorization, tenant);
        HookDef def;
        try {
            def = new HookDef(
                    Objects.requireNonNullElse(body.source(), ""),
                    Objects.requireNonNullElse(body.http(), List.of()),
                    Objects.requireNonNullElse(body.timeoutMs(), HookDef.DEFAULT_TIMEOUT_MS));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new Refusal(String.valueOf(e.getMessage())));
        }
        Map<String, Object> ctx = new LinkedHashMap<>(Objects.requireNonNullElse(body.ctx(), Map.of()));
        if (body.options() != null) ctx.put("options", body.options());
        List<String> logs = new ArrayList<>();
        long started = System.nanoTime();
        try {
            Object result = hooks.test(tenant, "test", def, ctx, logs::add);
            long millis = (System.nanoTime() - started) / 1_000_000;
            String effect = effect(result, body.options());
            return ResponseEntity.ok(new HookRun(result, null, !effect.startsWith("hook_error"), effect, logs, millis));
        } catch (HookException e) {
            long millis = (System.nanoTime() - started) / 1_000_000;
            return ResponseEntity.ok(
                    new HookRun(null, e.getMessage(), false, "hook_error: the hook did not return", logs, millis));
        }
    }

    /** What the engine does with a hook's result; mirrors {@code Engine}'s handling of {@link HookOutput}. */
    static String effect(@Nullable Object result, @Nullable List<String> options) {
        Optional<HookOutput> output = HookOutput.parse(result);
        if (output.isEmpty()) return "hook_error: not {}, {set}, {fail} or {pick}";
        return switch (output.get()) {
            case HookOutput.None none -> "nothing";
            case HookOutput.Set set ->
                options == null
                        ? "set "
                                + new TreeMap<>(set.vars())
                                        .entrySet().stream()
                                                .map(e -> "vars." + e.getKey() + " = " + e.getValue())
                                                .collect(Collectors.joining(", "))
                        : "hook_error: the hook of a choose may not set vars";
            case HookOutput.Fail fail -> "fail the step: " + fail.reason();
            case HookOutput.Pick pick -> {
                if (options == null) yield "hook_error: only the hook of a choose may pick";
                yield options.contains(pick.option())
                        ? "pick " + pick.option()
                        : "hook_error: " + pick.option() + " is not an enabled option";
            }
        };
    }

    @GetMapping("/admin/tenants/{tenant}/settings")
    SettingsView settings(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization) {
        authorize(authorization, tenant);
        return view(tenant);
    }

    /**
     * Saves settings over the tenant's config. Refused if the tenant's current version would fail
     * the checker under them: publish a flow that passes first, then tighten the settings.
     */
    @PutMapping("/admin/tenants/{tenant}/settings")
    ResponseEntity<?> saveSettings(
            @PathVariable String tenant,
            @RequestHeader(name = "Authorization", required = false) @Nullable String authorization,
            @RequestBody TenantSettings body) {
        authorize(authorization, tenant);
        String problem = problem(body);
        if (problem != null) return ResponseEntity.badRequest().body(new Refusal(problem));
        Optional<FlowVersion> current = versions.current(tenant);
        if (current.isPresent()) {
            Report report =
                    publisher.check(tenants.policy(tenant, body), current.get().flow(), Map.of());
            if (!report.ok()) return ResponseEntity.unprocessableEntity().body(Validation.of(report));
        }
        store.saveSettings(tenant, body);
        return ResponseEntity.ok(view(tenant));
    }

    private SettingsView view(String tenant) {
        return new SettingsView(
                tenants.settings(tenant),
                tenants.configured(tenant),
                store.settings(tenant).isPresent(),
                tenants.egress(tenant).stream().sorted().toList());
    }

    private @Nullable String problem(TenantSettings s) {
        Set<String> known = handlers.all().stream().map(Handler::name).collect(Collectors.toSet());
        if (s.handlers() != null && !known.containsAll(s.handlers())) return "unknown handler in handlers";
        if (s.screens() != null
                && !s.screens().stream().allMatch(t -> ScreenType.of(t).isPresent())) {
            return "unknown screen type in screens";
        }
        return s.branding().problem();
    }

    private void authorize(@Nullable String authorization) {
        auth.check(authorization);
    }

    private void authorize(@Nullable String authorization, String tenant) {
        auth.check(authorization, tenant);
    }

    private org.springframework.core.io.Resource configured(String tenant) {
        // authorize() let only configured tenants through
        return Objects.requireNonNull(props.tenants().get(tenant)).flow();
    }

    /** A flow sent here carries hooks inline as {@code source}, or names a file next to the configured flow. */
    private FlowCompiler.Compiled compile(String tenant, String yaml) {
        return EngineConfiguration.compileDraft(configured(tenant), tenant, yaml);
    }

    /** The hook files {@code yaml} names that exist next to the tenant's configured flow. */
    private Map<String, String> files(String tenant, String yaml) {
        Map<String, String> files = new TreeMap<>();
        try {
            FlowCompiler.compileWithHooks(tenant + ".yaml", yaml, name -> {
                Optional<String> text = EngineConfiguration.sibling(configured(tenant), name);
                text.ifPresent(t -> files.put(name, t));
                return text;
            });
        } catch (DslException e) {
            // Hooks compile before steps, so the files are known even when the flow has errors
        }
        return files;
    }
}
