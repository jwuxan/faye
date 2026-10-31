package faye.check;

import java.util.stream.Collectors;

/** A flow was refused because the checker found errors. */
public class CheckFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Report report;

    public CheckFailed(Report report) {
        super(report.errors().stream().map(Finding::toString).collect(Collectors.joining("\n")));
        this.report = report;
    }

    public Report report() {
        return report;
    }
}
