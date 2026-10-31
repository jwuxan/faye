package faye.app;

import faye.engine.TraceEvent;
import java.util.List;

/**
 * Preview traces: each preview flow's events, and a feed per tenant that the Admin UI follows
 * live. Events are numbered per tenant in the order they were appended.
 */
interface TraceStore {

    /**
     * @param seq position in the tenant's feed, from 1
     * @param at when it was appended, epoch milliseconds
     */
    record Recorded(long seq, String tenantId, String flowId, long at, TraceEvent event) {}

    void append(String tenantId, String flowId, TraceEvent event);

    /** A flow's events, oldest first; empty for a flow that was not a preview, or has expired. */
    List<Recorded> flow(String flowId);

    /** The tenant's recent events numbered after {@code after}, oldest first. */
    List<Recorded> feed(String tenantId, long after);

    /** The number of the tenant's latest event, or 0. */
    long latest(String tenantId);
}
