package faye.dsl;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A block of the YAML surface (ADR 0030): one built in step, with its handler and its screen
 * fixed, which a flow places and orders but never takes apart. A flow names a block by {@link
 * #id} ({@code block: password}) and may change only the {@link #settings} it declares ({@code
 * with: { attempts: 5 }}); everything else comes from the implementation. Implementations live
 * beside their handlers, outside the core, and the caller hands them to the compiler ({@link
 * FlowCompiler.Options#blocks}): the compiler knows none (ADR 0025).
 *
 * <p>A block lowers to {@code (screen screen options) (call handler)}, the options being {@link
 * #fixed} and the screen options its settings turn on, wrapped in {@code retry} when its attempts
 * are more than one. The IR stays as it was: blocks are a surface over the catalogs.
 */
public interface BlockSpec {

    /** How a flow names the block ({@code password}). */
    String id();

    /** How diagnostics and the console name it ({@code Password}). */
    String title();

    /** The handler its step calls. */
    String handler();

    /** The screen its step shows. */
    String screen();

    /** Where it may go. */
    Role role();

    /** Screen options it always sets, whatever the flow says. */
    default Map<String, Object> fixed() {
        return Map.of();
    }

    /** What a flow may change under {@code with}, by name, each with its default. */
    default Map<String, Setting> settings() {
        return Map.of();
    }

    /**
     * Why these defaults cannot replace the block's own, or null: each names one of its settings,
     * with the setting's type (an attempts count of 1 or more).
     */
    default @Nullable String problem(Map<String, Object> defaults) {
        for (var e : defaults.entrySet()) {
            Setting s = settings().get(e.getKey());
            Object v = e.getValue();
            if (s == null) return "the " + title() + " block has no setting '" + e.getKey() + "'";
            if (s.byDefault() instanceof Boolean && !(v instanceof Boolean)) {
                return "'" + e.getKey() + "' of the " + title() + " block is true or false";
            }
            if (s.byDefault() instanceof Integer && !(v instanceof Integer n && n >= 1)) {
                return "'" + e.getKey() + "' of the " + title() + " block is a whole number of 1 or more";
            }
        }
        return null;
    }

    /**
     * This block with other defaults for some of its settings (a tenant's configuration, ADR 0030):
     * a flow's {@code with} still wins. The caller checks them with {@link #problem} first.
     */
    default BlockSpec withDefaults(Map<String, Object> defaults) {
        if (defaults.isEmpty()) return this;
        BlockSpec block = this;
        Map<String, Setting> settings = new java.util.LinkedHashMap<>(settings());
        defaults.forEach((name, v) -> {
            Setting s = settings.get(name);
            if (s != null) settings.put(name, new Setting(v, s.target()));
        });
        Map<String, Setting> merged = Map.copyOf(settings);
        return new BlockSpec() {
            @Override
            public String id() {
                return block.id();
            }

            @Override
            public String title() {
                return block.title();
            }

            @Override
            public String handler() {
                return block.handler();
            }

            @Override
            public String screen() {
                return block.screen();
            }

            @Override
            public Role role() {
                return block.role();
            }

            @Override
            public Map<String, Object> fixed() {
                return block.fixed();
            }

            @Override
            public Map<String, Setting> settings() {
                return merged;
            }
        };
    }

    /** Where a block may go. */
    enum Role {
        /**
         * Finds the account from what the person typed: the first step of the sign in, always
         * run, and nowhere else. With its {@link Setting.Target#PASSWORD} setting on, it asks for
         * the password on the same screen and verifies it, and no {@link #PASSWORD} block may.
         */
        IDENTIFIER,
        /** The credential the identifier can ask for on its own screen; a {@link #CREDENTIAL} otherwise. */
        PASSWORD,
        /** Proves a credential, and may be an option of a choice. */
        CREDENTIAL,
        /** Acts for the person a credential verified (registers a passkey), and may be an option. */
        ACTION,
        /** Records consent: a step of its own, never in a group or a choice. */
        CONSENT;

        boolean option() {
            return this == PASSWORD || this == CREDENTIAL || this == ACTION;
        }
    }

    /**
     * A setting a flow may change.
     *
     * @param byDefault its value when the flow does not set it: a Boolean or an Integer, which is
     *     also the type the flow must write
     */
    record Setting(Object byDefault, Target target) {

        public Setting {
            if (!(byDefault instanceof Boolean || byDefault instanceof Integer)) {
                throw new IllegalArgumentException("a setting is a Boolean or an Integer");
            }
        }

        /** What a setting changes. */
        public enum Target {
            /** A screen option of the same name, set when true. */
            OPTION,
            /** The identifier's screen option that asks for the password too; see {@link Role#IDENTIFIER}. */
            PASSWORD,
            /** How many times the step runs before it fails; more than one wraps it in a retry. */
            ATTEMPTS
        }
    }
}
