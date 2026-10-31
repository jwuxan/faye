package faye.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.engine.HookException;
import faye.engine.HookStore;
import faye.engine.InMemoryHookStore;
import faye.engine.TenantPolicy;
import faye.ir.Hook;
import faye.ir.HookDef;
import faye.ir.SourceRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SandboxedHooksTest {

    static final QuickJsRuntime RUNTIME = new QuickJsRuntime(2);
    static final Egress SCORE = (target, query, timeout) -> Map.of("score", 90);
    static final HookDef RISK = new HookDef(
            "export default async (ctx, cap) => ({ set: { score: (await cap.http.getJson('ip-reputation')).score } })",
            List.of("ip-reputation"),
            500);

    static Hook hook(HookDef def) {
        return new Hook("risk", def.hash(), SourceRef.NONE);
    }

    @Test
    void grantedEgressIsReachable() {
        var store = new InMemoryHookStore();
        store.put(RISK);
        var hooks = new SandboxedHooks(
                store, RUNTIME, SCORE, Map.of("t", new TenantPolicy(false, null, null, Set.of("ip-reputation"))));
        assertThat(hooks.run("t", hook(RISK), Map.of())).isEqualTo(Map.of("set", Map.of("score", 90)));
    }

    /** A grant taken away after publishing holds at run time: the call fails inside the hook. */
    @Test
    void egressNotGrantedToTheTenantIsRefusedAtRunTime() {
        var store = new InMemoryHookStore();
        store.put(RISK);
        var hooks = new SandboxedHooks(store, RUNTIME, SCORE, Map.of());
        assertThatThrownBy(() -> hooks.run("t", hook(RISK), Map.of()))
                .isInstanceOf(HookException.class)
                .hasMessageContaining("not granted");
    }

    @Test
    void storeFailuresFailTheHookNotTheRequest() {
        HookStore broken = new HookStore() {
            @Override
            public void put(HookDef def) {}

            @Override
            public Optional<HookDef> get(String hash) {
                throw new IllegalStateException("redis down");
            }
        };
        var hooks = new SandboxedHooks(broken, RUNTIME, SCORE, Map.of());
        assertThatThrownBy(() -> hooks.run("t", hook(RISK), Map.of()))
                .isInstanceOf(HookException.class)
                .hasMessageContaining("redis down");
        assertThatThrownBy(() -> new SandboxedHooks(new InMemoryHookStore(), RUNTIME, SCORE, Map.of())
                        .run("t", hook(RISK), Map.of()))
                .isInstanceOf(HookException.class)
                .hasMessageContaining("no hook stored");
    }
}
