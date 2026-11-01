package faye.handlers;

import faye.engine.CredentialIssuer;
import faye.engine.Effect;
import faye.engine.Env;
import faye.engine.Handler;
import faye.engine.HandlerMeta;
import faye.engine.HandlerMeta.EffectKind;
import faye.engine.Invocation;
import faye.engine.Outcome;
import faye.engine.spi.Account;
import faye.engine.spi.CodeOutbox;
import faye.engine.spi.CodeOutbox.Channel;
import faye.engine.spi.PhoneDirectory;
import faye.engine.spi.UserDirectory;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * One time codes Faye delivers by email or text message, and adding a phone number (ADR 0032): the
 * issuers of the {@code code_sent} and {@code phone_enroll} screens and the handlers that verify
 * their answers, {@code email_code.verify}, {@code sms_code.verify} and {@code phone.enroll}.
 *
 * <p>The issuer generates the code, hands it to the {@link CodeOutbox} and keeps only {@code
 * HMAC-SHA256(key, flowId ‖ code)} in the waiting screen's data, which the handler compares in
 * constant time; the code itself is never presented, never in {@code Env} and never in a hook's
 * reach. The destination always comes from the account (its verified email, or its verified phone
 * with phone codes on), never from the submission, the flow or a hook; only {@code phone_enroll}
 * takes a number from the person, which is the number being proved.
 *
 * <p>Enumeration: for an unknown account, or one without the destination, the issuer records a
 * MAC no code can match and an outbox row that is never delivered, and presents the identifier as
 * typed, masked; the screen and its cost are a real account's, and every code is {@code
 * invalid_code}.
 *
 * <p>Fixed values, not settings (charter principle 4): 6 digits, valid 5 minutes, a resend 30
 * seconds after the last send, 3 sends per screen. Limits across flows are the outbox's.
 */
public final class DeliveredCodes {

    /** How many digits a code has. */
    public static final int DIGITS = 6;
    /** How long a code is good for. */
    public static final long LIFETIME_MILLIS = 5 * 60_000L;
    /** How long after a send the next may go. */
    public static final long COOLDOWN_MILLIS = 30_000L;
    /** How many codes one screen may send. */
    public static final int MAX_SENDS = 3;

    /** A code sent too soon after the last, or past a limit; shown on the screen. */
    public static final String SEND_LIMITED = "send_limited";
    /** A code past its lifetime; retryable, so the person can resend. */
    public static final String CODE_EXPIRED = "code_expired";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NEVER = "x";

    private DeliveredCodes() {}

    /**
     * The issuers of {@code code_sent} and {@code phone_enroll}.
     *
     * @param key the server key codes are MACed with; at least 32 bytes, the same for every process
     *     that may resume a flow
     */
    public static List<CredentialIssuer> issuers(UserDirectory users, CodeOutbox outbox, byte[] key, Clock clock) {
        Macs macs = new Macs(key);
        return List.of(new CodeSent(users, outbox, macs, clock), new PhoneEnroll(outbox, macs, clock));
    }

    /** {@code email_code.verify}, {@code sms_code.verify} and {@code phone.enroll}. */
    public static List<Handler> handlers(UserDirectory users, PhoneDirectory phones, byte[] key, Clock clock) {
        Macs macs = new Macs(key);
        return List.of(
                new CodeVerify(Channel.EMAIL, users, macs, clock),
                new CodeVerify(Channel.SMS, users, macs, clock),
                new PhoneEnrollVerify(users, phones, macs, clock));
    }

    // ---- issuers ----

    private record CodeSent(UserDirectory users, CodeOutbox outbox, Macs macs, Clock clock)
            implements CredentialIssuer {
        @Override
        public String screen() {
            return "code_sent";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            throw new IllegalStateException("a delivered code is issued for a flow");
        }

        /** A retry after a wrong code keeps the code that was sent; a new visit sends one. */
        @Override
        public Map<String, Object> issue(
                Env env, Context context, Map<String, Object> opts, Map<String, Object> previous) {
            if (previous.get("mac") instanceof String) return issuedKeys(previous);
            Channel channel = channel(opts);
            Target target = target(env, context, channel);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("to", target.masked());
            out.put("sends", 0L);
            out.put("sent", 0L);
            out.putAll(send(context, channel, target, 0L));
            return out;
        }

        @Override
        public Acted act(
                Env env,
                Context context,
                Map<String, Object> opts,
                Map<String, Object> issued,
                String action,
                Map<String, Object> input) {
            if (!"resend".equals(action)) throw new IllegalArgumentException(action);
            long now = clock.millis();
            if (!may(issued, now)) return Acted.refused(SEND_LIMITED);
            Channel channel = channel(opts);
            Map<String, Object> sent = send(context, channel, target(env, context, channel), number(issued, "sends"));
            return sent.isEmpty() ? Acted.refused(SEND_LIMITED) : Acted.done(sent);
        }

        /** A new code to the target, or nothing when the outbox refused it. */
        private Map<String, Object> send(Context context, Channel channel, Target target, long sends) {
            String code = code();
            String limitKey = target.destination() != null ? target.destination() : target.typed();
            if (!outbox.enqueue(context.tenant(), channel, target.destination(), limitKey, code)) {
                // Nothing went out: the screen waits with a code no one can know
                return sends == 0 ? Map.of("mac", macs.of(context.flowId(), NEVER), "expires", 0L) : Map.of();
            }
            long now = clock.millis();
            String matchable = target.destination() == null ? NEVER : code;
            return Map.of(
                    "mac",
                    macs.of(context.flowId(), matchable),
                    "expires",
                    now + LIFETIME_MILLIS,
                    "sent",
                    now,
                    "sends",
                    sends + 1);
        }

        /** The account's destination on the channel, if any; the identifier as typed otherwise. */
        private Target target(Env env, Context context, Channel channel) {
            String typed = env.user().get("identifier") instanceof String s ? s : "";
            Optional<Account> account = account(users, env, context.tenant(), context.preview());
            @Nullable
            String destination = account.map(a -> switch (channel) {
                        case EMAIL -> a.emailVerified() ? a.identifier() : null;
                        case SMS -> a.phone();
                    })
                    .orElse(null);
            String masked = destination == null
                    ? (channel == Channel.SMS ? maskPhone("") : maskEmail(typed))
                    : (channel == Channel.SMS ? maskPhone(destination) : maskEmail(destination));
            return new Target(destination, typed, masked);
        }

        @Override
        public boolean resumable(Env env, Map<String, Object> issued) {
            return issued.get("mac") instanceof String
                    && issued.get("to") instanceof String
                    && issued.get("expires") instanceof Number
                    && issued.get("sent") instanceof Number
                    && issued.get("sends") instanceof Number;
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("to", issued.get("to"));
            out.putAll(timing(issued, clock.millis()));
            return out;
        }
    }

    private record PhoneEnroll(CodeOutbox outbox, Macs macs, Clock clock) implements CredentialIssuer {
        @Override
        public String screen() {
            return "phone_enroll";
        }

        /** Nothing until the person asks for a code to the number they typed. */
        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            if (env.auth().subject() == null) {
                throw new IllegalStateException("adding a phone needs a signed in subject");
            }
            if (previous.get("phone") instanceof String) return issuedKeys(previous);
            return Map.of("sends", 0L, "sent", 0L);
        }

        @Override
        public Acted act(
                Env env,
                Context context,
                Map<String, Object> opts,
                Map<String, Object> issued,
                String action,
                Map<String, Object> input) {
            if (!"send".equals(action) || !(input.get("phone") instanceof String phone)) {
                throw new IllegalArgumentException(action);
            }
            long now = clock.millis();
            if (!may(issued, now)) return Acted.refused(SEND_LIMITED);
            String code = code();
            if (!outbox.enqueue(context.tenant(), Channel.SMS, phone, phone, code)) return Acted.refused(SEND_LIMITED);
            return Acted.done(Map.of(
                    "phone",
                    phone,
                    "mac",
                    macs.of(context.flowId(), phone + ":" + code),
                    "expires",
                    now + LIFETIME_MILLIS,
                    "sent",
                    now,
                    "sends",
                    number(issued, "sends") + 1));
        }

        @Override
        public boolean resumable(Env env, Map<String, Object> issued) {
            return env.auth().subject() != null
                    && issued.get("sends") instanceof Number
                    && issued.get("sent") instanceof Number;
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (issued.get("phone") instanceof String phone) {
                out.put("to", maskPhone(phone));
                out.putAll(timing(issued, clock.millis()));
            }
            return out;
        }
    }

    // ---- handlers ----

    /** Verifies a code sent on one channel; binds the account it was sent to. */
    private record CodeVerify(Channel channel, UserDirectory users, Macs macs, Clock clock) implements Handler {
        @Override
        public String name() {
            return channel == Channel.EMAIL ? "email_code.verify" : "sms_code.verify";
        }

        @Override
        public HandlerMeta meta() {
            return new HandlerMeta(
                    Set.of(channel.wireName()), true, false, false, EffectKind.NONE, HandlerMeta.NO_ARGS);
        }

        @Override
        public Outcome invoke(Invocation call) {
            Map<String, Object> context = call.input().context();
            if (!channel.wireName().equals(context.get("channel"))) {
                throw new IllegalStateException(name() + " must answer a code_sent screen of its channel");
            }
            Optional<Account> user = account(users, call.env(), call.tenant(), call.preview());
            String code = call.input().string("code");
            if (expired(context, clock)) return Outcome.fail(CODE_EXPIRED);
            return user.isPresent() && code != null && macs.matches(context.get("mac"), call.flowId(), code)
                    ? Outcome.subject(user.get().subject())
                    : Outcome.fail("invalid_code");
        }
    }

    /** Proves the person holds the number sent a code; stores it and turns phone codes on. */
    private record PhoneEnrollVerify(UserDirectory users, PhoneDirectory phones, Macs macs, Clock clock)
            implements Handler {
        @Override
        public String name() {
            return "phone.enroll";
        }

        @Override
        public HandlerMeta meta() {
            return new HandlerMeta(Set.of(), false, false, true, EffectKind.WRITE, HandlerMeta.NO_ARGS);
        }

        @Override
        public Outcome invoke(Invocation call) {
            String subject = call.env().auth().subject();
            if (subject == null
                    || users.bySubject(subject)
                            .filter(u -> visible(u, call.tenant(), call.preview()))
                            .isEmpty()) {
                return Outcome.fail("unauthenticated");
            }
            // "Not now": nothing is written, and the person is asked again next time
            if (Boolean.TRUE.equals(call.input().payload().get("skip"))) return Outcome.ok();
            Map<String, Object> context = call.input().context();
            String code = call.input().string("code");
            if (!(context.get("phone") instanceof String phone) || code == null) return Outcome.fail("invalid_code");
            if (expired(context, clock)) return Outcome.fail(CODE_EXPIRED);
            if (!macs.matches(context.get("mac"), call.flowId(), phone + ":" + code)) {
                return Outcome.fail("invalid_code");
            }
            Effect enroll = new Effect(name(), Map.of("subject", subject, "phone", phone));
            return new Outcome.Ok(Map.of("has_phone", true), null, null, List.of(enroll));
        }

        /** Idempotent: the same number again writes the same row. */
        @Override
        public @Nullable String commit(Effect effect) {
            Map<String, Object> d = effect.data();
            if (!(d.get("subject") instanceof String subject) || !(d.get("phone") instanceof String phone)) {
                throw new IllegalStateException("effect data without subject or phone");
            }
            phones.enroll(subject, phone);
            return null;
        }
    }

    // ---- helpers ----

    private record Target(@Nullable String destination, String typed, String masked) {}

    /** HMAC-SHA256 under the server key, bound to the flow. */
    private static final class Macs {
        private final SecretKeySpec key;

        Macs(byte[] key) {
            if (key.length < 32) throw new IllegalArgumentException("a code key needs at least 32 bytes");
            this.key = new SecretKeySpec(key.clone(), "HmacSHA256");
        }

        String of(String flowId, String code) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(key);
                mac.update(flowId.getBytes(StandardCharsets.UTF_8));
                mac.update((byte) 0);
                byte[] out = mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException(e);
            }
        }

        boolean matches(@Nullable Object stored, String flowId, String code) {
            return stored instanceof String s
                    && MessageDigest.isEqual(
                            s.getBytes(StandardCharsets.UTF_8), of(flowId, code).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Channel channel(Map<String, Object> opts) {
        return "sms".equals(opts.get("channel")) ? Channel.SMS : Channel.EMAIL;
    }

    private static String code() {
        StringBuilder out = new StringBuilder(DIGITS);
        for (int i = 0; i < DIGITS; i++) out.append(RANDOM.nextInt(10));
        return out.toString();
    }

    /** Whether another code may go now: past the cooldown, and under the sends of one screen. */
    private static boolean may(Map<String, Object> issued, long now) {
        return number(issued, "sends") < MAX_SENDS && now - number(issued, "sent") >= COOLDOWN_MILLIS;
    }

    private static boolean expired(Map<String, Object> context, Clock clock) {
        return !(context.get("expires") instanceof Number e) || clock.millis() >= e.longValue();
    }

    /** Seconds until a resend may go and until the code expires, as the Login UI counts them down. */
    private static Map<String, Object> timing(Map<String, Object> issued, long now) {
        long resend = number(issued, "sends") >= MAX_SENDS
                ? -1
                : Math.max(0, number(issued, "sent") + COOLDOWN_MILLIS - now) / 1000;
        long expires = Math.max(0, number(issued, "expires") - now) / 1000;
        Map<String, Object> out = new LinkedHashMap<>();
        // No more resends: the field is absent
        if (resend >= 0) out.put("resendIn", resend);
        out.put("expiresIn", expires);
        return out;
    }

    /** The keys this module's issuers write, kept across a retry. */
    private static Map<String, Object> issuedKeys(Map<String, Object> previous) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("to", "phone", "mac", "expires", "sent", "sends")) {
            Object v = previous.get(k);
            if (v != null) out.put(k, v);
        }
        return out;
    }

    private static long number(Map<String, Object> data, String key) {
        return data.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    /** The account the flow identified or bound, if this flow may see it. */
    private static Optional<Account> account(UserDirectory users, Env env, String tenant, boolean preview) {
        String subject = env.auth().subject();
        Optional<Account> user = subject != null
                ? users.bySubject(subject)
                : env.user().get("identifier") instanceof String id ? users.byIdentifier(id) : Optional.empty();
        return user.filter(u -> visible(u, tenant, preview));
    }

    private static boolean visible(Account user, String tenant, boolean preview) {
        return (!user.preview() || preview)
                && (user.tenant() == null || user.tenant().equals(tenant));
    }

    /** {@code a•••@example.com}; text without an {@code @} keeps only its first character. */
    static String maskEmail(String address) {
        if (address.isEmpty()) return "•••";
        int at = address.indexOf('@');
        String first = address.substring(0, address.offsetByCodePoints(0, 1));
        return at > 0 ? first + "•••" + address.substring(at) : first + "•••";
    }

    /** {@code ••• ••• 4821}: the last four digits only. */
    static String maskPhone(String number) {
        return "••• ••• " + (number.length() >= 4 ? number.substring(number.length() - 4) : "••••");
    }
}
