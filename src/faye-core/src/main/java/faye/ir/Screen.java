package faye.ir;

import java.util.Map;

public record Screen(String type, Map<String, Object> opts, SourceRef source) implements Node {

    public Screen {
        opts = Map.copyOf(opts);
    }
}
