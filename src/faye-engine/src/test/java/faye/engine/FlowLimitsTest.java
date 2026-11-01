package faye.engine;

import static org.assertj.core.api.Assertions.assertThat;

import faye.ir.IrCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every static limit of {@link FlowLimits}: a flow at the limit passes, one past it does not. */
class FlowLimitsTest {

    static faye.ir.Flow read(String body) {
        return IrCodec.readFlow("[\"flow\",1,\"f\",1,[\"entry\",\"login\",\"authenticate\"," + body + "]]");
    }

    static String seq(String... children) {
        return "[\"seq\"," + String.join(",", children) + "]";
    }

    static String steps(int n) {
        String[] steps = new String[n];
        for (int i = 0; i < n; i++) steps[i] = "[\"step\",\"s" + i + "\",[\"fail\",\"x\"]]";
        return seq(steps);
    }

    static String sets(int n) {
        String[] sets = new String[n];
        for (int i = 0; i < n; i++) sets[i] = "[\"set\",\"k" + i + "\",1]";
        return seq(sets);
    }

    static String hooks(int attachments, int distinct) {
        String[] hooks = new String[attachments];
        for (int i = 0; i < attachments; i++) {
            hooks[i] = "[\"hook\",\"h" + i + "\",\"sha256:" + "0".repeat(63) + (i % distinct) + "\"]";
        }
        return seq(hooks);
    }

    static List<String> messages(faye.ir.Flow flow) {
        return FlowLimits.check(flow).stream().map(Verifier.Problem::message).toList();
    }

    /** {@code n} writes of one name, for counting nodes without crossing the set names limit. */
    static String writes(int n) {
        String[] writes = new String[n];
        java.util.Arrays.fill(writes, "[\"set\",\"k\",1]");
        return seq(writes);
    }

    @Test
    void nodesAtMost1000() {
        // 999 sets + their seq is the 1000th node
        assertThat(FlowLimits.check(read(writes(999)))).isEmpty();
        assertThat(messages(read(writes(1000)))).containsExactly("a flow has at most 1000 nodes");
    }

    @Test
    void stepsAtMost64() {
        assertThat(FlowLimits.check(read(steps(64)))).isEmpty();
        assertThat(messages(read(steps(65)))).containsExactly("a flow has at most 64 steps");
    }

    @Test
    void hookAttachmentsAtMost16() {
        assertThat(FlowLimits.check(read(hooks(16, 1)))).isEmpty();
        assertThat(messages(read(hooks(17, 1)))).containsExactly("a flow has at most 16 hook attachments");
    }

    @Test
    void distinctHooksAtMost8() {
        assertThat(FlowLimits.check(read(hooks(8, 8)))).isEmpty();
        assertThat(messages(read(hooks(9, 9)))).containsExactly("a flow pins at most 8 distinct hooks");
    }

    @Test
    void optionsAtMost8() {
        assertThat(FlowLimits.check(read("[\"choose\",[" + optList(8) + "]]"))).isEmpty();
        List<Verifier.Problem> over = FlowLimits.check(read("[\"choose\",[" + optList(9) + "]]"));
        assertThat(over.stream().map(Verifier.Problem::message)).containsExactly("a choose has at most 8 options");
    }

    private static String optList(int n) {
        String[] opts = new String[n];
        for (int i = 0; i < n; i++) opts[i] = "[\"opt\",\"o" + i + "\",[\"fail\",\"x\"]]";
        return String.join(",", opts);
    }

    @Test
    void retryProductAtMost10() {
        String body = "[\"retry\",10,[\"retry\",1,[\"fail\",\"x\"]]]";
        assertThat(FlowLimits.check(read(body))).isEmpty();
        faye.ir.Flow over = read("[\"retry\",10,[\"retry\",2,[\"fail\",\"x\"]]]");
        List<Verifier.Problem> problems = FlowLimits.check(over);
        assertThat(problems.stream().map(Verifier.Problem::message))
                .containsExactly("retries on one path multiply to more than 10 attempts");
        // The innermost retry is the one that pushes the product past 10
        faye.ir.Entry entry = java.util.Objects.requireNonNull(over.entries().get("login"));
        faye.ir.Retry outer = (faye.ir.Retry) entry.body();
        assertThat(problems.get(0).source()).isEqualTo(((faye.ir.Retry) outer.body()).source());
    }

    @Test
    void setNamesAtMost32() {
        assertThat(FlowLimits.check(read(sets(32)))).isEmpty();
        assertThat(messages(read(sets(33)))).containsExactly("a flow writes at most 32 distinct names");
    }

    @Test
    void literalsAtMost1024Characters() {
        String at = "[\"set\",\"k\",\"" + "x".repeat(1024) + "\"]";
        assertThat(FlowLimits.check(read(seq(at)))).isEmpty();
        String over = "[\"set\",\"k\",\"" + "x".repeat(1025) + "\"]";
        assertThat(messages(read(seq(over)))).containsExactly("a literal is at most 1024 characters");
        String predicate = "[\"when\",{\"eq\":[\"vars.k\",\"" + "y".repeat(1025) + "\"]},[\"fail\",\"x\"]]";
        assertThat(messages(read(seq(predicate)))).containsExactly("a literal is at most 1024 characters");
    }

    @Test
    void namesAtMost64Characters() {
        String ok = "[\"step\",\"" + "s".repeat(64) + "\",[\"fail\",\"x\"]]";
        assertThat(FlowLimits.check(read(seq(ok)))).isEmpty();
        String step = "[\"step\",\"" + "s".repeat(65) + "\",[\"fail\",\"x\"]]";
        assertThat(messages(read(seq(step)))).containsExactly("a step id is at most 64 characters");
        String reason = "[\"fail\",\"" + "r".repeat(65) + "\"]";
        assertThat(messages(read(seq(reason)))).containsExactly("a fail reason is at most 64 characters");
        String set = "[\"set\",\"" + "k".repeat(65) + "\",1]";
        assertThat(messages(read(seq(set)))).containsExactly("a set name is at most 64 characters");
    }
}
