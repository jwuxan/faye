package faye.app;

import faye.check.CheckFailed;
import faye.check.Publisher;
import faye.check.Report;
import faye.clients.ClientDirectory;
import faye.dsl.BlockSpec;
import faye.dsl.DslException;
import faye.dsl.FlowCompiler;
import faye.engine.Engine;
import faye.engine.FlowHost;
import faye.engine.FlowStore;
import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.engine.Handler;
import faye.engine.HandlerRegistry;
import faye.engine.HookRunner;
import faye.engine.HookStore;
import faye.engine.ScreenRegistry;
import faye.engine.ScreenSpec;
import faye.hooks.HttpEgress;
import faye.hooks.QuickJsRuntime;
import faye.hooks.SandboxedHooks;
import faye.host.hydra.HydraClientDirectory;
import faye.host.hydra.HydraFlowHost;
import faye.host.mock.MockFlowHost;
import faye.identity.IdentityBlock;
import faye.identity.IdentityHandlers;
import faye.identity.IdentityIssuers;
import faye.identity.Passwords;
import faye.identity.User;
import faye.identity.UserStore;
import faye.identity.WebAuthnSettings;
import faye.ir.Entry;
import faye.ir.Flow;
import faye.screens.BuiltinScreen;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class EngineConfiguration {

    @Bean
    FlowVersions flowVersions(JdbcClient db, TransactionTemplate tx, FayeProperties props) {
        return new SqliteFlowVersions(db, tx, props.flowTtl(), Clock.systemUTC());
    }

    @Bean
    HookStore hookStore(JdbcClient db) {
        return new SqliteHookStore(db);
    }

    @Bean
    TenantConfigStore tenantConfigStore(JdbcClient db) {
        return new SqliteTenantConfigStore(db);
    }

    @Bean
    Tenants tenants(FayeProperties props, TenantConfigStore store) {
        return new Tenants(props, store);
    }

    /** Hooks run in QuickJS on WebAssembly, eight at a time, reaching only the configured targets. */
    @Bean
    SandboxedHooks hookRunner(HookStore hooks, FayeProperties props, Tenants tenants) {
        Map<String, URI> targets = new HashMap<>();
        props.egress().forEach((name, url) -> {
            if (url != null && !url.isBlank()) targets.put(name, URI.create(url));
        });
        return new SandboxedHooks(hooks, new QuickJsRuntime(8), new HttpEgress(targets), tenants);
    }

    /**
     * Publishes each tenant's configured flow unless the tenant published it before, then checks
     * every tenant's current version against its settings, which may have changed since it was
     * published. A flow that fails the checker stops the server from starting. The configured YAML
     * is kept as the source of the version it compiles to.
     */
    @Bean
    Publisher publisher(
            FlowVersions versions,
            HookStore hooks,
            HandlerRegistry handlers,
            ScreenRegistry screens,
            FayeProperties props,
            Tenants tenants,
            TenantConfigStore store,
            FlowCompiler.Options dsl) {
        Publisher publisher = new Publisher(versions, hooks, handlers, screens, tenants);
        props.tenants().forEach((id, tenant) -> {
            String problem = blocksProblem(dsl, tenants.configured(id));
            if (problem != null) throw new IllegalStateException("tenant " + id + " blocks: " + problem);
            // The configured file with the configured defaults only: defaults saved in the console
            // apply when a flow is next published from it, never to what a restart publishes
            FlowCompiler.Options options =
                    dsl.withDefaults(tenants.configured(id).blocks());
            FlowCompiler.Compiled compiled = compile(tenant.flow(), id, options);
            Flow flow = compiled.flow();
            String hash = FlowVersion.of(flow).hash();
            if (!versions.history(id).contains(hash)) {
                refuse(id, "flow", publisher.check(id, flow, compiled.hooks()));
                publisher.publish(id, flow, compiled.hooks());
            }
            String yaml = read(tenant.flow());
            store.saveSource(hash, yaml);
            // A source kept from before a DSL change that left the IR alone (consent moving into
            // login.steps, ADR 0026) no longer compiles; this file compiles to the same version
            if (!compiles(tenant.flow(), id, store.source(hash).orElse(yaml), options)) store.replaceSource(hash, yaml);
            FlowVersion current = versions.current(id).orElseThrow();
            refuse(id, "current version " + current.hash(), publisher.check(id, current.flow()));
        });
        return publisher;
    }

    static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void refuse(String tenant, String what, Report report) {
        if (!report.ok()) {
            throw new IllegalStateException(
                    "tenant " + tenant + " " + what + " fails the checker:\n" + new CheckFailed(report).getMessage());
        }
    }

    /**
     * What the compiler takes from this app, since it knows no host and no catalog: the namespace
     * spelling tenant YAML may still use (this app's flows were written for Hydra, and say {@code
     * hydra.} for {@code host.}; the alias comes from the host module), and the screens and handlers
     * that belong in consent entries only, which start a flow's consent steps (ADR 0026), and the
     * blocks of its handlers (ADR 0030).
     */
    @Bean
    FlowCompiler.Options dslOptions(HandlerRegistry handlers, ScreenRegistry screens) {
        Set<Entry.Kind> consentOnly = Set.of(Entry.Kind.CONSENT);
        return new FlowCompiler.Options(
                HydraFlowHost.DSL_PATH_ALIASES,
                screens.all().stream()
                        .filter(s -> s.entries().equals(consentOnly))
                        .map(ScreenSpec::type)
                        .collect(Collectors.toSet()),
                handlers.all().stream()
                        .filter(h -> h.meta().entries().equals(consentOnly))
                        .map(Handler::name)
                        .collect(Collectors.toSet()),
                blocks());
    }

    /** The compiler's options for a tenant: its blocks take the tenant's defaults (ADR 0030). */
    static FlowCompiler.Options forTenant(FlowCompiler.Options dsl, Tenants tenants, String tenant) {
        return dsl.withDefaults(tenants.settings(tenant).blocks());
    }

    /** Why a tenant's block defaults cannot be used, or null. */
    static @Nullable String blocksProblem(FlowCompiler.Options dsl, TenantSettings settings) {
        for (var e : settings.blocks().entrySet()) {
            BlockSpec block = dsl.blocks().get(e.getKey());
            if (block == null) return "unknown block '" + e.getKey() + "'";
            String problem = block.problem(e.getValue());
            if (problem != null) return problem;
            // Which blocks may follow the identifier depends on its view: the flow says it
            for (String name : e.getValue().keySet()) {
                if (block.settings().get(name) instanceof BlockSpec.Setting s
                        && s.target() == BlockSpec.Setting.Target.PASSWORD) {
                    return "the " + block.title() + " block's '" + name + "' is set in the flow, not as a default";
                }
            }
        }
        return null;
    }

    /** The blocks a flow's steps may be, by id: the identity module's (ADR 0030). */
    static Map<String, BlockSpec> blocks() {
        return Arrays.stream(IdentityBlock.values()).collect(Collectors.toMap(BlockSpec::id, b -> b));
    }

    /** Compiles a configured flow; a hook's {@code file:} is read from next to it. */
    static FlowCompiler.Compiled compile(Resource resource, String tenant, FlowCompiler.Options dsl) {
        return FlowCompiler.compileWithHooks(
                Objects.requireNonNullElse(resource.getFilename(), tenant),
                read(resource),
                name -> sibling(resource, name),
                dsl);
    }

    /** Compiles a flow sent through the admin API; a hook's {@code file:} is read from next to the configured one. */
    static FlowCompiler.Compiled compileDraft(
            Resource configured, String tenant, String yaml, FlowCompiler.Options dsl) {
        return FlowCompiler.compileWithHooks(tenant + ".yaml", yaml, name -> sibling(configured, name), dsl);
    }

    private static boolean compiles(Resource configured, String tenant, String yaml, FlowCompiler.Options dsl) {
        try {
            compileDraft(configured, tenant, yaml, dsl);
            return true;
        } catch (DslException e) {
            return false;
        }
    }

    /** A hook file next to a configured flow. */
    static Optional<String> sibling(Resource flow, String name) {
        try {
            Resource file = flow.createRelative(name);
            return file.exists() ? Optional.of(read(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Each tenant's user pool, in the database the deploy seed writes accounts to. Configured demo
     * accounts (tests only; none in the shipped config) are written over at startup.
     */
    @Bean
    UserStore userStore(JdbcClient db, TransactionTemplate tx, FayeProperties props) {
        UserStore users = new SqliteUserStore(db, tx);
        props.demoUsers()
                .forEach(u -> users.save(new User(
                        u.subject(),
                        IdentityHandlers.normalize(u.identifier()),
                        u.name(),
                        true,
                        Passwords.hash(u.password()),
                        u.totpSecret(),
                        -1,
                        List.of(),
                        false,
                        false,
                        u.tenant())));
        return users;
    }

    @Bean
    HandlerRegistry handlerRegistry(UserStore users, FayeProperties props) {
        var webauthn =
                new WebAuthnSettings(props.webauthn().rpId(), props.webauthn().origins());
        return new HandlerRegistry(IdentityHandlers.create(users, webauthn, Clock.systemUTC()));
    }

    @Bean
    FlowStore flowStore(JdbcClient db, FayeProperties props) {
        return new SqliteFlowStore(db, props.flowTtl(), Clock.systemUTC());
    }

    @Bean
    TraceStore traceStore(JdbcClient db, TransactionTemplate tx, FayeProperties props) {
        return new SqliteTraceStore(db, tx, props.flowTtl(), Clock.systemUTC());
    }

    @Bean
    DraftPreviews previews(
            FayeProperties props,
            TenantConfigStore store,
            FlowVersions versions,
            HookStore hooks,
            Publisher publisher,
            Tenants tenants,
            TraceStore traces,
            FlowCompiler.Options dsl) {
        return new DraftPreviews(props, store, versions, hooks, publisher, tenants, traces, dsl);
    }

    @Bean
    @ConditionalOnExpression("!'${faye.hydra-admin-url:}'.isEmpty()")
    FlowHost hydraFlowHost(FayeProperties props) {
        return new HydraFlowHost(URI.create(props.hydraAdminUrl()));
    }

    @Bean
    @ConditionalOnExpression("!'${faye.hydra-admin-url:}'.isEmpty()")
    ClientDirectory hydraClientDirectory(FayeProperties props) {
        return new HydraClientDirectory(URI.create(props.hydraAdminUrl()));
    }

    /** The in memory host has no registered clients: any challenge loads as client {@code demo}. */
    @Bean
    @ConditionalOnExpression("'${faye.hydra-admin-url:}'.isEmpty()")
    ClientDirectory mockClientDirectory() {
        return List::of;
    }

    /**
     * Without Hydra, challenges are served by the in memory host, as client {@code demo} of the
     * default tenant, or of the first configured tenant when there is no default one.
     */
    @Bean
    @ConditionalOnExpression("'${faye.hydra-admin-url:}'.isEmpty()")
    FlowHost mockFlowHost(FayeProperties props) {
        String tenant = props.tenants().containsKey(Engine.DEFAULT_TENANT)
                ? null
                : props.tenants().keySet().stream().findFirst().orElse(null);
        return new MockFlowHost("http://localhost/mock-callback", tenant);
    }

    /**
     * The screens of the screen protocol, which the Login UI renders, with the credential issuers of
     * the identity module whose handlers verify what they issue.
     */
    @Bean
    ScreenRegistry screens() {
        return BuiltinScreen.registry(IdentityIssuers.create());
    }

    /** Starts once the publisher has published and checked every tenant's flow. */
    @Bean
    @DependsOn("publisher")
    Engine engine(
            FlowVersions versions,
            HandlerRegistry handlers,
            ScreenRegistry screens,
            FlowStore store,
            FlowHost host,
            HookRunner hooks,
            Tenants tenants,
            DraftPreviews previews) {
        return Engine.builder()
                .versions(versions)
                .handlers(handlers)
                .store(store)
                .host(host)
                .tenants(tenants)
                .hooks(hooks)
                .previews(previews)
                .screens(screens)
                .build();
    }
}
