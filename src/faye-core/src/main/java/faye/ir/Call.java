package faye.ir;

import java.util.Map;

public record Call(String handler, Map<String, Object> args, SourceRef source) implements Node {

    public Call {
        args = Map.copyOf(args);
    }
}
