package faye.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import faye.ir.HookDef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The sandbox itself: hostile hook source must end in a {@link HookException}, never a hang or a host call. */
class QuickJsRuntimeTest {

    final QuickJsRuntime runtime = new QuickJsRuntime(4);
    final List<String> logged = new CopyOnWriteArrayList<>();
    final List<String> called = new CopyOnWriteArrayList<>();

    /** Answers target {@code ip-reputation} with a score of 90; refuses everything else. */
    final Capabilities cap = new Capabilities() {
        @Override
        public @Nullable Object getJson(String target, Map<String, Object> query, Duration timeout) {
            called.add(target + " " + query);
            if (!"ip-reputation".equals(target)) throw new HookException("egress target " + target + " is not granted");
            return Map.of("score", 90);
        }

        @Override
        public void log(String line) {
            logged.add(line);
        }
    };

    static final Map<String, Object> CTX = Map.of(
            "request", Map.of("ip", "203.0.113.9"),
            "user", Map.of("identifier", "alice@example.com"),
            "vars", Map.of());

    @Nullable
    Object run(String source) {
        return runtime.run(new HookDef(source, List.of(), 200), CTX, cap);
    }

    @Test
    void riskHookCallsEgressAndSetsAVar() {
        Object out = run("""
                export default async function (ctx, cap) {
                  const score = await cap.http.getJson('ip-reputation', { ip: ctx.request.ip })
                  cap.log.info('score', score.score)
                  return { set: { risk: score.score > 50 ? 'high' : 'low' } }
                }
                """);
        assertThat(out).isEqualTo(Map.of("set", Map.of("risk", "high")));
        assertThat(called).containsExactly("ip-reputation {ip=203.0.113.9}");
        assertThat(logged).containsExactly("score 90");
    }

    @Test
    void infiniteLoopIsStoppedAtTheDeadlineAndTheRuntimeRecovers() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> run("export default function () { for (;;) {} }"))
                .isInstanceOf(HookException.class)
                .hasMessageContaining("deadline");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        // Every worker is free again: four runs at once all complete
        List<CompletableFuture<@Nullable Object>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(CompletableFuture.supplyAsync(() -> run("export default () => ({})")));
        }
        runs.forEach(r -> assertThat(r.join()).isEqualTo(Map.of()));
    }

    /** With one worker, every run after a loop needs that worker back. */
    @Test
    void theOnlyWorkerComesBackAfterALoop() {
        var single = new QuickJsRuntime(1);
        HookDef loop = new HookDef("export default function () { for (;;) {} }", List.of(), 50);
        assertThatThrownBy(() -> single.run(loop, CTX, cap)).isInstanceOf(HookException.class);
        for (int i = 0; i < 3; i++) {
            assertThat(single.run(
                            new HookDef("export default () => ({ set: { i: " + i + " } })", List.of(), 200), CTX, cap))
                    .isEqualTo(Map.of("set", Map.of("i", i)));
        }
    }

    /** Compiling has its own budget: a large hook's first run fits in a short deadline. */
    @Test
    void firstRunOfALargeHookDoesNotSpendItsDeadlineCompiling() {
        String padding = "// " + "x".repeat(70) + "\n";
        String source = padding.repeat(700) + "export default () => ({ set: { ok: true } })";
        assertThat(runtime.run(new HookDef(source, List.of(), 50), CTX, cap))
                .isEqualTo(Map.of("set", Map.of("ok", true)));
    }

    @Test
    void logLinesCannotForgeLogEntries() {
        run("export default (ctx, cap) => { cap.log.info('a\\nWARN forged'); return {} }");
        assertThat(logged).containsExactly("a WARN forged");
    }

    @Test
    void loopAfterAnAwaitIsStoppedToo() {
        assertThatThrownBy(() -> run("""
                        export default async function (ctx, cap) {
                          await cap.http.getJson('ip-reputation', {})
                          while (true) {}
                        }
                        """)).isInstanceOf(HookException.class).hasMessageContaining("deadline");
    }

    @Test
    void memoryBombHitsTheCapNotTheHeap() {
        String bomb = "export default function () { const a = []; for (;;) a.push('x'.repeat(1e6) + a.length) }";
        assertThatThrownBy(() -> runtime.run(new HookDef(bomb, List.of(), 2000), CTX, cap))
                .isInstanceOf(HookException.class);
        assertThat(QuickJsRuntime.MAX_PAGES * 64 * 1024).isEqualTo(16 * 1024 * 1024);
        // The JVM is unharmed and the next hook runs
        assertThat(run("export default () => ({ set: { ok: true } })")).isEqualTo(Map.of("set", Map.of("ok", true)));
    }

    @Test
    void ctxHasNoAuthAndIsFrozen() {
        Object out = run("""
                export default function (ctx) {
                  'use strict'
                  let threw = false
                  try { ctx.vars.risk = 'low' } catch (e) { threw = true }
                  return { set: { auth: typeof ctx.auth, frozen: threw && Object.isFrozen(ctx.user) } }
                }
                """);
        assertThat(out).isEqualTo(Map.of("set", Map.of("auth", "undefined", "frozen", true)));
    }

    @Test
    void noHostAccessOrAmbientIo() {
        Object out = run("""
                export default function () {
                  const names = ['Java', 'Packages', 'process', 'require', 'fetch', 'XMLHttpRequest',
                                 'setTimeout', 'std', 'os', 'Polyglot', 'load', 'quit']
                  return { set: { present: names.filter(n => typeof globalThis[n] !== 'undefined').join(',') } }
                }
                """);
        assertThat(out).isEqualTo(Map.of("set", Map.of("present", "")));
    }

    @Test
    void nothingSurvivesFromOneRunToTheNext() {
        String source =
                "export default () => { globalThis.n = (globalThis.n || 0) + 1; return { set: { n: globalThis.n } } }";
        assertThat(run(source)).isEqualTo(Map.of("set", Map.of("n", 1)));
        assertThat(run(source)).isEqualTo(Map.of("set", Map.of("n", 1)));
    }

    @Test
    void egressRefusalIsAnErrorTheHookCanCatch() {
        Object out = run("""
                export default async function (ctx, cap) {
                  try { await cap.http.getJson('internal-admin', {}) } catch (e) { return { set: { risk: 'high' } } }
                  return { set: { risk: 'low' } }
                }
                """);
        assertThat(out).isEqualTo(Map.of("set", Map.of("risk", "high")));
    }

    @Test
    void callingTheHostFunctionsDirectlyStillGoesThroughTheGrantCheck() {
        Object out =
                run("export default () => ({ set: { r: JSON.stringify(faye_cap.getJson('internal-admin', {})) } })");
        assertThat(out)
                .isEqualTo(Map.of("set", Map.of("r", "{\"error\":\"egress target internal-admin is not granted\"}")));
        assertThat(called).containsExactly("internal-admin {}");
    }

    @Test
    void thrownErrorsAndRejectionsAreHookErrors() {
        assertThatThrownBy(() -> run("export default () => { throw new Error('boom') }"))
                .isInstanceOf(HookException.class)
                .hasMessageContaining("boom");
        assertThatThrownBy(() -> run("export default async () => { throw new Error('later') }"))
                .isInstanceOf(HookException.class)
                .hasMessageContaining("later");
        assertThatThrownBy(() -> run("export default () => Promise.reject(7)")).isInstanceOf(HookException.class);
    }

    @Test
    void sourceWithoutADefaultExportOrWithSyntaxErrorsFails() {
        assertThatThrownBy(() -> run("function hook() { return {} }"))
                .isInstanceOf(HookException.class)
                .hasMessageContaining("default export");
        assertThatThrownBy(() -> run("export default function ( {")).isInstanceOf(HookException.class);
        assertThatThrownBy(() -> run("import x from 'fs'\nexport default () => ({})"))
                .isInstanceOf(HookException.class);
    }

    @Test
    void whateverTheHookReturnsComesBackRawForTheEngineToJudge() {
        assertThat(run("export default () => ({ succeed: true })")).isEqualTo(Map.of("succeed", true));
        assertThat(run("export default () => undefined")).isNull();
    }

    @Test
    void tooManyRunsAtOnceAreRefusedNotQueuedForever() {
        var small = new QuickJsRuntime(1);
        HookDef slow = new HookDef("export default function () { for (;;) {} }", List.of(), 300);
        List<CompletableFuture<Object>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    small.run(slow, CTX, cap);
                    return "ran";
                } catch (HookException e) {
                    return e.getMessage();
                }
            }));
        }
        assertThat(runs.stream().map(CompletableFuture::join).toList()).contains("too many hooks running");
    }
}
