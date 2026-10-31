package faye.ir;

/** Where an IR node came from: a YAML file position, or an editor node id with no line. */
public record SourceRef(String origin, int line, int column) {

    public static final SourceRef NONE = new SourceRef("", 0, 0);

    @Override
    public String toString() {
        return line > 0 ? origin + ":" + line + ":" + column : origin;
    }
}
