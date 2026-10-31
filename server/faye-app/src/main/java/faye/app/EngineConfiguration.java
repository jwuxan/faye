package faye.app;

import faye.check.CheckFailed;
import faye.check.Publisher;
import faye.check.Report;
import faye.dsl.FlowCompiler;
import faye.engine.Engine;
import faye.engine.FlowHost;
import faye.engine.FlowStore;
import faye.engine.FlowVersion;
import faye.engine.FlowVersions;
import faye.engine.HandlerRegistry;
import faye.engine.HookRunner;
import faye.engine.HookStore;
import faye.hooks.HttpEgress;
import faye.hooks.QuickJsRuntime;
import faye.hooks.SandboxedHooks;
import faye.host.hydra.HydraFlowHost;
import faye.host.mock.MockFlowHost;
import faye.identity.IdentityHandlers;
import faye.identity.InMemoryUserStore;
import faye.identity.Passwords;
import faye.identity.User;
import faye.identity.UserStore;
import faye.identity.WebAuthnSettings;
import faye.ir.Flow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class EngineConfiguration {

    @Bean
    FlowVersions flowVersions(StringRedisTemplate redis) {
        return new RedisFlowVersions(redis);
    }

    @Bean
    HookStore hookStore(StringRedisTemplate redis) {
        return new RedisHookStore(redis);
    }

    @Bean
    TenantConfigStore tenantConfigStore(StringRedisTemplate redis) {
        return new RedisTenantConfigStore(redis);
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
            FayeProperties props,
            Tenants tenants,
            TenantConfigStore store) {
        Publisher publisher = new Publisher(versions, hooks, handlers, tenants);
        props.tenants().forEach((id, tenant) -> {
            FlowCompiler.Compiled compiled = compile(tenant.flow(), id);
            Flow flow = compiled.flow();
            String hash = FlowVersion.of(flow).hash();
            if (!versions.history(id).contains(hash)) {
                refuse(id, "flow", publisher.check(id, flow, compiled.hooks()));
                publisher.publish(id, flow, compiled.hooks());
            }
            store.saveSource(hash, read(tenant.flow()));
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

    /** Compiles a configured flow; a hook's {@code file:} is read from next to it. */
    static FlowCompiler.Compiled compile(Resource resource, String tenant) {
        return FlowCompiler.compileWithHooks(
                Objects.requireNonNullElse(resource.getFilename(), tenant),
                read(resource),
                name -> sibling(resource, name));
    }

    /** Compiles a flow sent through the admin API; a hook's {@code file:} is read from next to the configured one. */
    static FlowCompiler.Compiled compileDraft(Resource configured, String tenant, String yaml) {
        return FlowCompiler.compileWithHooks(tenant + ".yaml", yaml, name -> sibling(configured, name));
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

    /** The prototype's identity store: seeded demo accounts, enrollments kept in memory. */
    @Bean
    UserStore userStore(FayeProperties props) {
        UserStore users = new InMemoryUserStore();
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
                        false)));
        return users;
    }

    @Bean
    HandlerRegistry handlerRegistry(UserStore users, FayeProperties props) {
        var webauthn =
                new WebAuthnSettings(props.webauthn().rpId(), props.webauthn().origins());
        return new HandlerRegistry(IdentityHandlers.create(users, webauthn, Clock.systemUTC()));
    }

    @Bean
    FlowStore flowStore(StringRedisTemplate redis, FayeProperties props) {
        return new RedisFlowStore(redis, props.flowTtl());
    }

    @Bean
    TraceStore traceStore(StringRedisTemplate redis, FayeProperties props) {
        return new RedisTraceStore(redis, props.flowTtl());
    }

    @Bean
    DraftPreviews previews(
            FayeProperties props,
            TenantConfigStore store,
            FlowVersions versions,
            HookStore hooks,
            Publisher publisher,
            Tenants tenants,
            TraceStore traces) {
        return new DraftPreviews(props, store, versions, hooks, publisher, tenants, traces);
    }

    @Bean
    @ConditionalOnExpression("!'${faye.hydra-admin-url:}'.isEmpty()")
    FlowHost hydraFlowHost(FayeProperties props) {
        return new HydraFlowHost(URI.create(props.hydraAdminUrl()));
    }

    /** Without Hydra, challenges are served by the in memory host. */
    @Bean
    @ConditionalOnExpression("'${faye.hydra-admin-url:}'.isEmpty()")
    FlowHost mockFlowHost() {
        return new MockFlowHost();
    }

    /** Starts once the publisher has published and checked every tenant's flow. */
    @Bean
    @DependsOn("publisher")
    Engine engine(
            FlowVersions versions,
            HandlerRegistry handlers,
            FlowStore store,
            FlowHost host,
            HookRunner hooks,
            Tenants tenants,
            DraftPreviews previews) {
        return new Engine(versions, handlers, store, host, tenants, hooks, previews);
    }
}
