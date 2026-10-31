package faye.engine;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * One entry of the continuation stack. Nodes are referenced by their path of child indices within
 * the entry body of the pinned IR, so frames serialize to small JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Frame.Eval.class, name = "eval"),
    @JsonSubTypes.Type(value = Frame.SeqNext.class, name = "seq"),
    @JsonSubTypes.Type(value = Frame.StepExit.class, name = "step"),
    @JsonSubTypes.Type(value = Frame.RetryExit.class, name = "retry"),
    @JsonSubTypes.Type(value = Frame.Pick.class, name = "pick"),
})
public sealed interface Frame {

    List<Integer> path();

    /** Evaluate the node at {@code path}. */
    record Eval(List<Integer> path) implements Frame {}

    /** Continue the seq at {@code path} with child {@code next}. */
    record SeqNext(List<Integer> path, int next) implements Frame {}

    /** Leaving step {@code id}; marks the innermost enclosing step while below it on the stack. */
    record StepExit(List<Integer> path, String id) implements Frame {}

    /** Leaving the retry at {@code path} during its {@code attempt}th run (from 1); a failure below unwinds to here. */
    record RetryExit(List<Integer> path, int attempt) implements Frame {}

    /** The choose at {@code path} is waiting on the method picker; the submission names the option. */
    record Pick(List<Integer> path) implements Frame {}
}
