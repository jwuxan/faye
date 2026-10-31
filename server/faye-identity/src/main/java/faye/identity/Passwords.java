package faye.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Argon2id hashing with Spring Security's current defaults. */
public final class Passwords {

    private static final Argon2PasswordEncoder ARGON2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    /** Verified against for unknown accounts, so they cost the same time as known ones. */
    private static final String DUMMY = ARGON2.encode(UUID.randomUUID().toString());

    private Passwords() {}

    public static String hash(String password) {
        return ARGON2.encode(password);
    }

    /** Always does the work of one Argon2 verification, whether or not there is a hash. */
    static boolean matches(String password, @Nullable String hash) {
        boolean match = ARGON2.matches(password, hash == null ? DUMMY : hash);
        return hash != null && match;
    }
}
