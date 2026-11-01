package faye.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import faye.ir.HookDef;
import io.roastedroot.quickjs4j.core.Builtins;
import io.roastedroot.quickjs4j.core.Engine;
import io.roastedroot.quickjs4j.core.GuestFunction;
import io.roastedroot.quickjs4j.core.HostFunction;
import io.roastedroot.quickjs4j.core.Invokables;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import run.endive.runtime.ByteArrayMemory;
import run.endive.wasm.types.MemoryLimits;

/**
 * {@link HookRuntime} on QuickJS compiled to WebAssembly, run by a pure Java Wasm runtime
 * (quickjs4j on Endive). Each run gets a fresh QuickJS instance whose linear memory is capped, so
 * nothing survives from one run to the next and a hook cannot allocate past the cap. The only
 * imports are the two host functions behind {@code cap}; QuickJS has no file, network, clock
 * timer or process access of its own. A run past its deadline is interrupted on its worker thread.
 */
public final class QuickJsRuntime implements HookRuntime {

    /** Wasm pages of 64 KiB: 16 MiB of linear memory per run. */
    static final int MAX_PAGES = 256;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DEFAULT_EXPORT = Pattern.compile("(?m)^export default ");
    private static final int MAX_LOG_LINES = 20;
    private static final int MAX_LOG_LINE = 500;
    private static final int MAX_ERROR = 200;

    /**
     * Appended to the hook. {@code faye_cap} (the host functions) answers with {@code {value}} or
     * {@code {error}}, since a Java exception would not reach the hook's try/catch; every thrown
     * error or rejected promise comes back as {@code {error}}.
     */
    private static final String GLUE = """

            ;
            function faye_freeze(o) {
              if (o !== null && typeof o === 'object' && !Object.isFrozen(o)) {
                Object.freeze(o);
                Object.values(o).forEach(faye_freeze);
              }
              return o;
            }
            async function faye_run(input) {
              const cap = faye_freeze({
                http: {
                  getJson: async (target, query) => {
                    const r = faye_cap.getJson(String(target), query === undefined ? {} : query);
                    if (r.error !== undefined) throw new Error(r.error);
                    return r.value;
                  },
                },
                log: { info: (...args) => { faye_cap.log(args.map(String).join(' ')); } },
              });
              try {
                if (typeof faye_hook !== 'function') throw new Error('the hook has no default export function');
                const result = await faye_hook(faye_freeze(input), cap);
                return { ok: result === undefined ? null : result };
              } catch (e) {
                return { error: String((e && e.message) || e) };
              }
            }
            """;

    private static final HookDef WARM_UP = new HookDef("export default () => ({})", List.of(), 1);

    private static final Capabilities NO_CAPABILITIES = new Capabilities() {
        @Override
        public @Nullable Object getJson(String target, Map<String, Object> query, Duration timeout) {
            throw new HookException("no capabilities");
        }

        @Override
        public void log(String line) {}
    };

    private final ThreadPoolExecutor pool;
    /** QuickJS bytecode by hook hash; hooks are immutable, so an entry never goes stale. */
    private final Map<String, byte[]> compiled = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 256;
        }
    });

    /**
     * Creates the runtime.
     *
     * @param concurrency hooks that may run at once, each in at most 16 MiB of Wasm memory; as many
     *     more may wait, and further runs are refused
     */
    public QuickJsRuntime(int concurrency) {
        this.pool = new ThreadPoolExecutor(
                concurrency, concurrency, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(concurrency), r -> {
                    Thread t = new Thread(r, "faye-hook");
                    t.setDaemon(true);
                    return t;
                });
        // Loading the Wasm runtime takes longer than a hook's deadline; do it once, up front.
        invoke(compile(WARM_UP), JSON.createObjectNode(), NO_CAPABILITIES, Long.MAX_VALUE);
    }

    /**
     * Compiling a hook (once per hash, up to 64 KiB of source) has its own budget, so the first run
     * of a large hook after a restart does not spend its deadline on it.
     */
    static final int COMPILE_TIMEOUT_MS = 2000;

    @Override
    public @Nullable Object run(HookDef def, Map<String, Object> ctx, Capabilities cap) {
        byte[] code = compiled.get(def.hash());
        if (code == null) {
            code = timed(() -> compile(def), COMPILE_TIMEOUT_MS, "compiling");
            compiled.put(def.hash(), code);
        }
        byte[] bytecode = code;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(def.timeoutMs());
        JsonNode input = JSON.valueToTree(ctx);
        JsonNode out = timed(() -> invoke(bytecode, input, cap, deadline), def.timeoutMs(), "running");
        if (out != null && out.has("error")) {
            String error = out.get("error").asText();
            throw new HookException("threw: " + error.substring(0, Math.min(error.length(), MAX_ERROR)));
        }
        if (out == null || !out.has("ok")) throw new HookException("returned no result");
        return JSON.convertValue(out.get("ok"), Object.class);
    }

    /** Runs {@code work} on the pool, interrupting it after {@code timeoutMs}. */
    private <T> T timed(Callable<T> work, int timeoutMs, String what) {
        Future<T> future;
        try {
            future = pool.submit(work);
        } catch (RejectedExecutionException e) {
            throw new HookException("too many hooks running", e);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HookException("exceeded its " + timeoutMs + " ms deadline " + what, e);
        } catch (ExecutionException e) {
            throw new HookException("failed " + what + ": " + e.getCause(), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new HookException("interrupted", e);
        }
    }

    private static byte[] compile(HookDef def) {
        String source = DEFAULT_EXPORT.matcher(def.source()).replaceFirst("const faye_hook = ") + GLUE;
        try (Engine compiler = engine(NO_CAPABILITIES, Long.MAX_VALUE)) {
            return compiler.compilePortableGuestFunction(source);
        }
    }

    private static JsonNode invoke(byte[] code, JsonNode input, Capabilities cap, long deadline) {
        try (Engine engine = engine(cap, deadline)) {
            return (JsonNode) engine.invokePrecompiledGuestFunction("hook", "faye_run", List.of(input), code);
        }
    }

    /** A fresh QuickJS instance whose only imports are the two host functions behind {@code cap}. */
    private static Engine engine(Capabilities cap, long deadline) {
        int[] logged = {0};
        Builtins host = Builtins.builder("faye_cap")
                .add(new HostFunction("getJson", List.of(String.class, JsonNode.class), JsonNode.class, args -> {
                    ObjectNode out = JSON.createObjectNode();
                    try {
                        Map<String, Object> query = JSON.convertValue(
                                args.get(1),
                                JSON.getTypeFactory()
                                        .constructMapType(LinkedHashMap.class, String.class, Object.class));
                        Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
                        out.set("value", JSON.valueToTree(cap.getJson(String.valueOf(args.get(0)), query, left)));
                    } catch (HookException e) {
                        out.put("error", String.valueOf(e.getMessage()));
                    } catch (IllegalArgumentException e) {
                        out.put("error", "query must be an object of strings, numbers and booleans");
                    }
                    return out;
                }))
                .add(new HostFunction("log", List.of(String.class), JsonNode.class, args -> {
                    if (logged[0]++ < MAX_LOG_LINES) {
                        // One line per call: a hook cannot forge further server log entries
                        String line = String.valueOf(args.get(0)).replaceAll("\\p{Cntrl}", " ");
                        cap.log(line.substring(0, Math.min(line.length(), MAX_LOG_LINE)));
                    }
                    return JSON.nullNode();
                }))
                .build();
        return Engine.builder()
                .addBuiltins(host)
                .addInvokables(Invokables.builder("hook")
                        .add(new GuestFunction("faye_run", List.of(JsonNode.class), JsonNode.class))
                        .build())
                .withMemoryFactory(limits -> new ByteArrayMemory(new MemoryLimits(limits.initialPages(), MAX_PAGES)))
                .build();
    }
}
