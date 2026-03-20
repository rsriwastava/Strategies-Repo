package com.quantlab.strategies.core;

/**
 * Broker-agnostic order dispatch interface.
 * <p>
 * Implementations can target any broker protocol (FIX, REST, WebSocket)
 * or a paper-trading simulator. The interface is intentionally synchronous
 * at the call-site — implementations may delegate to async transports
 * internally, but callers treat dispatch as a blocking operation so that
 * the strategy lifecycle can reason about success/failure deterministically.
 * <p>
 * <b>Contract:</b>
 * <ul>
 *   <li>All methods must be thread-safe.</li>
 *   <li>Implementations must throw {@link IllegalStateException} if
 *       {@link #isConnected()} returns {@code false} at invocation time.</li>
 *   <li>Order placement methods must throw on outright failures (e.g.
 *       connection loss) rather than silently swallowing errors.</li>
 * </ul>
 */
public interface OrderService {

    /**
     * Places entry orders for all legs defined in the given signal.
     *
     * @param signal the signal whose legs should be entered
     * @throws IllegalStateException if the broker connection is down
     */
    void placeEntryOrders(Signal signal);

    /**
     * Places exit (close) orders for all open legs in the given signal.
     *
     * @param signal the signal whose open legs should be exited
     * @throws IllegalStateException if the broker connection is down
     */
    void placeExitOrders(Signal signal);

    /**
     * Places an exit order for a single leg within a signal.
     * Used for partial exits and individual leg management.
     *
     * @param signal the parent signal
     * @param leg    the specific leg to exit
     * @throws IllegalStateException if the broker connection is down
     */
    void placeSingleLegExit(Signal signal, StrategyLeg leg);

    /**
     * Places modification orders for rolling adjustments (e.g. strike
     * shifts, expiry rolls). The signal's legs should reflect the
     * desired target state after the adjustment.
     *
     * @param signal the signal with updated leg definitions
     * @throws IllegalStateException if the broker connection is down
     */
    void placeModificationOrders(Signal signal);

    /**
     * Queries the current status of a previously placed order.
     *
     * @param orderId broker-assigned or locally-tracked order identifier
     * @return the current {@link OrderStatus}, never {@code null}
     */
    OrderStatus getOrderStatus(String orderId);

    /**
     * Returns {@code true} if the broker connection is healthy and
     * orders can be dispatched.
     */
    boolean isConnected();
}
