package com.quantlab.strategies.core;

/**
 * Lifecycle states for an order dispatched through {@link OrderService}.
 * <p>
 * State transitions follow the standard exchange order lifecycle:
 * <pre>
 *   PENDING -> PLACED -> PARTIALLY_FILLED -> FILLED
 *                    \-> REJECTED
 *                    \-> CANCELLED
 *                    \-> EXPIRED
 * </pre>
 * {@code UNKNOWN} is reserved for cases where the broker does not return
 * a recognisable status or the connection is lost mid-flight.
 */
public enum OrderStatus {

    /** Order created locally but not yet sent to the broker. */
    PENDING,

    /** Order accepted by the broker/exchange and is on the book. */
    PLACED,

    /** Order has been partially filled; remaining quantity is still open. */
    PARTIALLY_FILLED,

    /** Order fully filled. */
    FILLED,

    /** Order rejected by the broker or exchange. */
    REJECTED,

    /** Order cancelled (either by user or by the system). */
    CANCELLED,

    /** Order expired without being filled (e.g. IOC/GTD timeout). */
    EXPIRED,

    /** Status could not be determined — treat as requiring manual review. */
    UNKNOWN;

    /**
     * Returns {@code true} if the order has reached a terminal state
     * and will not transition further.
     */
    public boolean isTerminal() {
        return this == FILLED || this == REJECTED || this == CANCELLED || this == EXPIRED;
    }

    /**
     * Returns {@code true} if the order is still active on the book
     * and may receive further fills.
     */
    public boolean isActive() {
        return this == PLACED || this == PARTIALLY_FILLED;
    }
}
