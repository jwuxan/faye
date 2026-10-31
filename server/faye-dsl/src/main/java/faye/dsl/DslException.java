package faye.dsl;

import java.util.List;
import java.util.stream.Collectors;

public class DslException extends RuntimeException {

    private final List<Diagnostic> diagnostics;

    public DslException(List<Diagnostic> diagnostics) {
        super(diagnostics.stream().map(Diagnostic::toString).collect(Collectors.joining("\n")));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
