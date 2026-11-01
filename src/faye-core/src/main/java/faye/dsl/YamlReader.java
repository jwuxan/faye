package faye.dsl;

import faye.ir.SourceRef;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * The YAML format adapter: reads YAML into a {@link Doc}. The only class that knows SnakeYAML.
 * Limits are set here rather than left to library defaults, and every failure is a {@link
 * DslException} at a position.
 */
final class YamlReader {

    /** Generous for any flow a person writes; a flow is a few KiB. */
    private static final int MAX_CODE_POINTS = 1024 * 1024;

    private static final int MAX_NESTING = 64;
    private static final int MAX_ALIASES = 50;

    private static final java.util.regex.Pattern DECIMAL = java.util.regex.Pattern.compile("[-+]?(0|[1-9][0-9]*)");

    private final String origin;
    private final List<Diagnostic> errors = new ArrayList<>();

    /** Nodes already read, by identity. */
    private final java.util.Set<Node> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private YamlReader(String origin) {
        this.origin = origin;
    }

    /**
     * A document, and what was wrong with it that does not stop reading: a duplicate key (the first
     * is kept) or a key that is not a string (dropped).
     */
    record Read(Doc doc, List<Diagnostic> errors) {}

    /**
     * Reads YAML to a document tree.
     *
     * @param origin file name used in source refs
     * @throws DslException on a syntax error or a limit, which leave no document
     */
    static Read read(String origin, String yaml) {
        YamlReader reader = new YamlReader(origin);
        return new Read(reader.read(yaml), List.copyOf(reader.errors));
    }

    private Doc read(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(MAX_CODE_POINTS);
        options.setNestingDepthLimit(MAX_NESTING);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        Node root;
        try {
            root = new Yaml(new SafeConstructor(options)).compose(new StringReader(yaml));
        } catch (MarkedYAMLException e) {
            var mark = e.getProblemMark();
            throw new DslException(List.of(new Diagnostic(
                    new SourceRef(origin, mark.getLine() + 1, mark.getColumn() + 1), String.valueOf(e.getProblem()))));
        } catch (YAMLException e) {
            // Limits (nesting, aliases, size) are reported without a mark
            throw new DslException(
                    List.of(new Diagnostic(new SourceRef(origin, 1, 1), String.valueOf(e.getMessage()))));
        } catch (IllegalArgumentException e) {
            // SnakeYAML refuses some malformed input outside its own exceptions (a tag such as "!%b"):
            // still the author's YAML, so a diagnostic, never an unchecked error (fuzz finding)
            throw new DslException(
                    List.of(new Diagnostic(new SourceRef(origin, 1, 1), "not valid YAML: " + e.getMessage())));
        }
        if (root == null) {
            throw new DslException(List.of(new Diagnostic(new SourceRef(origin, 1, 1), "empty document")));
        }
        return doc(root);
    }

    private Doc doc(Node node) {
        // An alias is the same node reached twice; a recursive one never ends. A
        // flow has no use for them, so the surface refuses anchors and aliases outright.
        if (!seen.add(node)) {
            errors.add(new Diagnostic(ref(node), "anchors and aliases are not supported; write the value out"));
            return new Doc.Scalar(null, ref(node));
        }
        return switch (node) {
            case MappingNode m -> {
                Map<String, Doc.Entry> entries = new LinkedHashMap<>();
                for (NodeTuple t : m.getValue()) {
                    if (!(t.getKeyNode() instanceof ScalarNode key)) {
                        errors.add(new Diagnostic(ref(t.getKeyNode()), "keys must be strings"));
                    } else if (entries.containsKey(key.getValue())) {
                        errors.add(new Diagnostic(ref(key), "duplicate key '" + key.getValue() + "'"));
                    } else {
                        entries.put(key.getValue(), new Doc.Entry(ref(key), doc(t.getValueNode())));
                    }
                }
                yield new Doc.Map(entries, ref(m));
            }
            case SequenceNode s -> {
                List<Doc> items = new ArrayList<>();
                s.getValue().forEach(n -> items.add(doc(n)));
                yield new Doc.Seq(items, ref(s));
            }
            case ScalarNode s -> new Doc.Scalar(scalar(s), ref(s));
            default -> {
                errors.add(new Diagnostic(ref(node), "unsupported YAML node"));
                yield new Doc.Scalar(null, ref(node));
            }
        };
    }

    /**
     * A scalar typed by its resolved tag. YAML 1.1 forms that read as another type than they look
     * (booleans spelled yes, no, on or off; integers in hex, octal, base 60 or with underscores) are
     * refused rather than retyped, so a value means what its author sees.
     */
    private @Nullable Object scalar(ScalarNode s) {
        String text = s.getValue();
        Tag tag = s.getTag();
        if (tag.equals(Tag.BOOL)) {
            if (!"true".equals(text) && !"false".equals(text)) {
                errors.add(new Diagnostic(ref(s), "write a boolean as true or false, or quote '" + text + "'"));
            }
            return "true".equals(text);
        }
        if (tag.equals(Tag.NULL)) return null;
        if (tag.equals(Tag.INT)) {
            try {
                if (!DECIMAL.matcher(text).matches()) throw new NumberFormatException(text);
                long l = Long.parseLong(text);
                return l == (int) l ? (Object) (int) l : l;
            } catch (NumberFormatException e) {
                errors.add(
                        new Diagnostic(ref(s), "write an integer in decimal within 64 bits, or quote '" + text + "'"));
                return text;
            }
        }
        if (tag.equals(Tag.FLOAT)) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return text;
            }
        }
        return text;
    }

    private SourceRef ref(Node node) {
        var mark = node.getStartMark();
        return new SourceRef(origin, mark.getLine() + 1, mark.getColumn() + 1);
    }
}
