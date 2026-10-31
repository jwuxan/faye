package faye.check;

import faye.engine.Acr;
import java.util.Comparator;
import java.util.List;

/** What the checker found: errors block publishing, warnings do not. */
public record Report(List<Finding> errors, List<Finding> warnings, List<FlowPath> paths) {

    public Report {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
        paths = List.copyOf(paths);
    }

    public boolean ok() {
        return errors.isEmpty();
    }

    /** e.g. {@code login: 3 success paths, 4 failure paths; assurance pwd (faye:1fa) to otp+pwd (faye:2fa)} */
    public String summary() {
        List<FlowPath> login =
                paths.stream().filter(p -> p.section().equals("login")).toList();
        List<FlowPath> success = login.stream().filter(FlowPath::succeeded).toList();
        String out =
                "login: " + success.size() + " success paths, " + (login.size() - success.size()) + " failure paths";
        if (success.isEmpty()) return out;
        Comparator<FlowPath> strength = Comparator.<FlowPath>comparingInt(
                        p -> p.acr() == null ? 0 : Acr.TWO_FACTOR.equals(p.acr()) ? 2 : 1)
                .thenComparingInt(p -> p.amr().size());
        FlowPath min = success.stream().min(strength).orElseThrow();
        FlowPath max = success.stream().max(strength).orElseThrow();
        return out + "; assurance " + assurance(min) + " to " + assurance(max);
    }

    private static String assurance(FlowPath p) {
        return p.amr().isEmpty()
                ? "none"
                : String.join("+", p.amr().stream().sorted().toList()) + " (" + p.acr() + ")";
    }
}
