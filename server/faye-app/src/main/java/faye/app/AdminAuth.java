package faye.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin API access: {@code Authorization: Bearer <faye.admin-token>}. With no token configured the
 * admin API does not exist (404); a wrong token is 401; a tenant that is not configured is 404.
 */
@Component
final class AdminAuth {

    private final FayeProperties props;
    private final Tenants tenants;

    AdminAuth(FayeProperties props, Tenants tenants) {
        this.props = props;
        this.tenants = tenants;
    }

    void check(@Nullable String authorization) {
        if (props.adminToken().isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        byte[] expected = ("Bearer " + props.adminToken()).getBytes(StandardCharsets.UTF_8);
        byte[] given = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    void check(@Nullable String authorization, String tenant) {
        check(authorization);
        if (!tenants.exists(tenant)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
