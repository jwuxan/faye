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
import faye.engine.spi.QrGrants;
import faye.engine.spi.QrGrants.Grant;
import faye.engine.spi.QrGrants.Status;
import faye.engine.spi.UserDirectory;
import faye.ir.Entry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * QR code sign in (ADR 0032): the issuers of the {@code qr} screen, which the device signing in
 * shows, and of the {@code qr_approve} screen, which the phone's approve flow shows, and the
 * handlers that answer them, {@code qr.redeem} and {@code qr.approve}.
 *
 * <p>The waiting device's issuer creates a grant and shows a URL with a 128 bit token and a two
 * digit number; the phone opens it, runs the tenant's approve entry, and its last step, {@code
 * qr.approve}, approves the grant for whoever that entry proved, once the person picked the same
 * number. The waiting device reads the approval with the screen's {@code poll} action and answers
 * the screen; {@code qr.redeem} consumes the grant once, for that flow only, and proves exactly the
 * amr the phone's flow proved. A grant lives 2 minutes.
 *
 * <p>The approver must be the account the waiting flow identified: a QR code approved by anyone
 * else fails {@code wrong_account}, so {@code user.*} never describes someone other than the
 * subject.
 */
public final class QrSignIn {

    /** How long a grant may wait for its approval. */
    public static final long LIFETIME_MILLIS = 120_000L;

    /** What {@code qr.redeem} may prove: any amr an approve entry can. */
    public static final Set<String> AMR = Set.of("pwd", "otp", "sms", "email", "hwk", "user");

    /** A grant past its lifetime; the retry shows a new QR code. */
    public static final String QR_EXPIRED = "qr_expired";
    /** Not approved yet, or refused on the phone. */
    public static final String QR_NOT_APPROVED = "qr_not_approved";

    private static final SecureRandom RANDOM = new SecureRandom();

    private QrSignIn() {}

    /**
     * The issuers of {@code qr} and {@code qr_approve}.
     *
     * @param approveUrl what the QR code encodes before the token: the Login UI's {@code /approve/}
     */
    public static List<CredentialIssuer> issuers(QrGrants grants, String approveUrl, Clock clock) {
        return List.of(new QrCode(grants, approveUrl, clock), new QrApprove(grants, clock));
    }

    /** {@code qr.redeem} and {@code qr.approve}. */
    public static List<Handler> handlers(QrGrants grants, UserDirectory users, Clock clock) {
        return List.of(new Redeem(grants, users, clock), new Approve(grants, clock));
    }

    /** The hash a grant is stored under: base64url SHA-256 of its token. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the waiting device ----

    private record QrCode(QrGrants grants, String approveUrl, Clock clock) implements CredentialIssuer {
        @Override
        public String screen() {
            return "qr";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            throw new IllegalStateException("a QR code is issued for a flow");
        }

        /** A retry keeps a grant still waiting; an expired, refused or used one gets a new code. */
        @Override
        public Map<String, Object> issue(
                Env env, Context context, Map<String, Object> opts, Map<String, Object> previous) {
            long now = clock.millis();
            if (previous.get("token") instanceof String token
                    && grants.find(hash(token))
                            .filter(g -> g.status() == Status.PENDING && now < g.expiresAt())
                            .isPresent()) {
                Map<String, Object> kept = new LinkedHashMap<>();
                for (String k : List.of("token", "number", "expires", "status")) kept.put(k, previous.get(k));
                return kept;
            }
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            int number = 10 + RANDOM.nextInt(90);
            Map<String, Object> client = new LinkedHashMap<>();
            if (env.client().get("client_id") instanceof String id) client.put("client_id", id);
            if (env.client().get("client_name") instanceof String name) client.put("client_name", name);
            grants.create(new Grant(
                    hash(token),
                    context.flowId(),
                    context.tenant(),
                    context.preview(),
                    client,
                    number,
                    device(env.request().get("user_agent")),
                    now + LIFETIME_MILLIS,
                    Status.PENDING,
                    null,
                    List.of()));
            return Map.of(
                    "token", token, "number", (long) number, "expires", now + LIFETIME_MILLIS, "status", "pending");
        }

        /** Reads where the grant is; nothing else changes. */
        @Override
        public Acted act(
                Env env,
                Context context,
                Map<String, Object> opts,
                Map<String, Object> issued,
                String action,
                Map<String, Object> input) {
            if (!(issued.get("token") instanceof String token)) throw new IllegalStateException("no QR code issued");
            Optional<Grant> grant =
                    grants.find(hash(token)).filter(g -> g.flowId().equals(context.flowId()));
            String status = grant.map(g -> switch (g.status()) {
                        case PENDING -> clock.millis() >= g.expiresAt() ? "expired" : "pending";
                        case APPROVED, CONSUMED -> "approved";
                        case REFUSED -> "refused";
                    })
                    .orElse("expired");
            return Acted.done(Map.of("status", status));
        }

        @Override
        public boolean resumable(Env env, Map<String, Object> issued) {
            return issued.get("token") instanceof String
                    && issued.get("number") instanceof Number
                    && issued.get("expires") instanceof Number
                    && issued.get("status") instanceof String;
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", approveUrl + issued.get("token"));
            out.put("number", issued.get("number"));
            long expires = issued.get("expires") instanceof Number e ? e.longValue() : 0L;
            out.put("expiresIn", Math.max(0, expires - clock.millis()) / 1000);
            out.put("status", issued.get("status"));
            return out;
        }
    }

    /** Proves what the phone's approve flow proved, for the account this flow identified. */
    private record Redeem(QrGrants grants, UserDirectory users, Clock clock) implements Handler {
        @Override
        public String name() {
            return "qr.redeem";
        }

        @Override
        public HandlerMeta meta() {
            return new HandlerMeta(AMR, true, false, false, EffectKind.GUARD, HandlerMeta.NO_ARGS);
        }

        @Override
        public Outcome invoke(Invocation call) {
            if (!(call.input().context().get("token") instanceof String token)) {
                throw new IllegalStateException("qr.redeem must answer a qr screen");
            }
            String hash = hash(token);
            long now = clock.millis();
            Optional<Grant> consumed = grants.consume(hash, call.flowId(), now);
            if (consumed.isEmpty()) {
                boolean expired = grants.find(hash)
                        .map(g -> g.status() == Status.PENDING && now >= g.expiresAt())
                        .orElse(true);
                return Outcome.fail(expired ? QR_EXPIRED : QR_NOT_APPROVED);
            }
            Grant grant = consumed.get();
            String subject = java.util.Objects.requireNonNull(grant.subject(), "an approved grant names its subject");
            if (!identified(call).map(a -> a.subject().equals(subject)).orElse(false)) {
                return Outcome.fail("wrong_account");
            }
            Set<String> amr = new java.util.HashSet<>(grant.amr());
            amr.retainAll(AMR);
            if (amr.isEmpty()) return Outcome.fail(QR_NOT_APPROVED);
            return new Outcome.Ok(Map.of(), subject, amr);
        }

        /** The account the flow identified, or the subject already bound, if this flow may see it. */
        private Optional<Account> identified(Invocation call) {
            Env env = call.env();
            String bound = env.auth().subject();
            Optional<Account> user = bound != null
                    ? users.bySubject(bound)
                    : env.user().get("identifier") instanceof String id ? users.byIdentifier(id) : Optional.empty();
            return user.filter(u -> (!u.preview() || call.preview())
                    && (u.tenant() == null || u.tenant().equals(call.tenant())));
        }
    }

    // ---- the phone ----

    private record QrApprove(QrGrants grants, Clock clock) implements CredentialIssuer {
        @Override
        public String screen() {
            return "qr_approve";
        }

        @Override
        public Map<String, Object> issue(Env env, Map<String, Object> opts, Map<String, Object> previous) {
            throw new IllegalStateException("a QR approval is issued for a flow");
        }

        /** Three numbers, one of them the waiting device's, in a random order. */
        @Override
        public Map<String, Object> issue(
                Env env, Context context, Map<String, Object> opts, Map<String, Object> previous) {
            if (env.auth().subject() == null) throw new IllegalStateException("approving needs a signed in subject");
            Grant grant = grants.find(context.ref())
                    .filter(g -> g.status() == Status.PENDING && clock.millis() < g.expiresAt())
                    .orElseThrow(() -> new IllegalStateException("no QR code waits for this approval"));
            if (previous.get("choices") instanceof List<?> kept && previous.get("grant") instanceof String g) {
                return Map.of("grant", g, "choices", kept, "device", grant.device());
            }
            List<Long> choices = new ArrayList<>(List.of((long) grant.number()));
            while (choices.size() < 3) {
                long n = 10L + RANDOM.nextInt(90);
                if (!choices.contains(n)) choices.add(n);
            }
            Collections.shuffle(choices, RANDOM);
            return Map.of("grant", context.ref(), "choices", choices, "device", grant.device());
        }

        @Override
        public boolean resumable(Env env, Map<String, Object> issued) {
            return env.auth().subject() != null
                    && issued.get("grant") instanceof String
                    && issued.get("choices") instanceof List<?>;
        }

        @Override
        public Map<String, Object> present(Env env, Map<String, Object> issued) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("numbers", issued.get("choices"));
            if (issued.get("device") instanceof String d && !d.isEmpty()) out.put("device", d);
            return out;
        }
    }

    /**
     * Approves the waiting device for whoever the approve entry proved, with what it proved, once
     * the person picked its number; the write is applied when the entry succeeds. A wrong number
     * refuses the grant at once, so the waiting device stops, and fails the entry.
     */
    private record Approve(QrGrants grants, Clock clock) implements Handler {
        @Override
        public String name() {
            return "qr.approve";
        }

        @Override
        public HandlerMeta meta() {
            return new HandlerMeta(
                    Set.of(), false, false, true, EffectKind.WRITE, HandlerMeta.NO_ARGS, Set.of(Entry.Kind.APPROVE));
        }

        @Override
        public Outcome invoke(Invocation call) {
            String subject = call.env().auth().subject();
            if (subject == null) return Outcome.fail("unauthenticated");
            Map<String, Object> context = call.input().context();
            if (!(context.get("grant") instanceof String grant)) {
                throw new IllegalStateException("qr.approve must answer a qr_approve screen");
            }
            Optional<Grant> waiting =
                    grants.find(grant).filter(g -> g.status() == Status.PENDING && clock.millis() < g.expiresAt());
            if (waiting.isEmpty()) return Outcome.fail(QR_EXPIRED);
            if (!(call.input().payload().get("number") instanceof Number picked)
                    || picked.longValue() != waiting.get().number()) {
                grants.refuse(grant);
                return Outcome.fail("wrong_number");
            }
            Effect approve = new Effect(
                    name(),
                    Map.of(
                            "grant",
                            grant,
                            "subject",
                            subject,
                            "amr",
                            call.env().auth().amr()));
            return new Outcome.Ok(Map.of(), null, null, List.of(approve));
        }

        @Override
        public @Nullable String commit(Effect effect) {
            Map<String, Object> d = effect.data();
            if (!(d.get("grant") instanceof String grant)
                    || !(d.get("subject") instanceof String subject)
                    || !(d.get("amr") instanceof List<?> amr)) {
                throw new IllegalStateException("effect data without grant, subject or amr");
            }
            List<String> proven = amr.stream().map(String::valueOf).toList();
            return grants.approve(grant, subject, proven, clock.millis()) ? null : QR_EXPIRED;
        }
    }

    /** What the phone shows of the waiting device: its browser family, from the user agent. */
    static String device(@Nullable Object userAgent) {
        if (!(userAgent instanceof String ua)) return "";
        String browser = ua.contains("Edg/")
                ? "Edge"
                : ua.contains("Firefox/")
                        ? "Firefox"
                        : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "A browser";
        String os = ua.contains("Windows")
                ? "Windows"
                : ua.contains("Mac OS X")
                        ? (ua.contains("iPhone") || ua.contains("iPad") ? "iOS" : "macOS")
                        : ua.contains("Android") ? "Android" : ua.contains("Linux") ? "Linux" : "";
        return os.isEmpty() ? browser : browser + " on " + os;
    }
}
