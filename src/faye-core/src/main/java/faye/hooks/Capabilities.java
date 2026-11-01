package faye.hooks;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What one hook run may do beyond computing: {@code cap.http.getJson} and {@code cap.log.info}. */
public interface Capabilities {

    /**
     * Calls a granted egress target over HTTP.
     *
     * @param query string, number or boolean values, sent as URL query parameters
     * @param timeout what is left of the hook's deadline
     * @return the target's JSON response
     * @throws HookException if the target is not granted, fails, or does not answer in time
     */
    @Nullable
    Object getJson(String target, Map<String, Object> query, Duration timeout);

    void log(String line);
}
