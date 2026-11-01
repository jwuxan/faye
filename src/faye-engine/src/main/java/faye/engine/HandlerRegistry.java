package faye.engine;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class HandlerRegistry {

    private final Map<String, Handler> handlers = new HashMap<>();

    public HandlerRegistry(List<? extends Handler> handlers) {
        for (Handler h : handlers) {
            if (this.handlers.put(h.name(), h) != null) {
                throw new IllegalArgumentException("duplicate handler " + h.name());
            }
        }
    }

    public Optional<Handler> find(String name) {
        return Optional.ofNullable(handlers.get(name));
    }

    public Collection<Handler> all() {
        return handlers.values();
    }
}
