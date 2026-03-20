package com.quantlab.strategies.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Abstract base strategy providing common lifecycle management for
 * all option strategies.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li><b>Idempotency guards</b> — CAS-based flags prevent concurrent
 *       entry or exit processing for the same strategy instance.</li>
 *   <li><b>Tick routing</b> — the {@link #check(long, String)} method
 *       routes each scheduler tick to the correct lifecycle phase
 *       (entry vs. exit evaluation) based on the current status.</li>
 *   <li><b>Broker-agnostic dispatch</b> — order placement is delegated
 *       to the injected {@link OrderService}.</li>
 *   <li><b>Market data access</b> — the injected {@link MarketDataProvider}
 *       provides prices and greeks without coupling to any feed.</li>
 * </ul>
 * <p>
 * Subclasses implement the four abstract hooks:
 * {@link #onEntry(long)}, {@link #shouldExit(long, long)},
 * {@link #onExit(long)}, and {@link #onExitEvaluation(long)}.
 *
 * <pre>{@code
 * public class IronCondorStrategy extends BaseStrategy {
 *     public IronCondorStrategy(StrategyConfig cfg, OrderService os, MarketDataProvider md) {
 *         super(cfg, os, md);
 *     }
 *     // implement abstract hooks ...
 * }
 * }</pre>
 */
public abstract class BaseStrategy {

    protected final StrategyConfig config;
    protected final OrderService orderService;
    protected final MarketDataProvider marketData;

    // Idempotency guards — one flag per strategy ID prevents overlapping ticks
    private final ConcurrentMap<Long, Boolean> entryInProgress = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Boolean> exitInProgress = new ConcurrentHashMap<>();

    /**
     * Creates a new strategy instance bound to the given configuration
     * and service dependencies.
     *
     * @param config       immutable strategy configuration
     * @param orderService broker-agnostic order dispatch
     * @param marketData   broker-agnostic market data provider
     */
    protected BaseStrategy(StrategyConfig config, OrderService orderService, MarketDataProvider marketData) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.orderService = Objects.requireNonNull(orderService, "orderService must not be null");
        this.marketData = Objects.requireNonNull(marketData, "marketData must not be null");
    }

    // ── Abstract hooks — implemented by each concrete strategy ───────────

    /**
     * Entry logic invoked when the strategy is in ACTIVE or EXIT status
     * and ready to place new positions.
     *
     * @param strategyId the strategy instance identifier
     */
    protected abstract void onEntry(long strategyId);

    /**
     * Evaluates whether the current live position should be closed.
     *
     * @param strategyId the strategy instance identifier
     * @param signalId   the active signal identifier
     * @return {@code true} if the position should be exited
     */
    protected abstract boolean shouldExit(long strategyId, long signalId);

    /**
     * Executes a full exit of all open legs for the given strategy.
     *
     * @param strategyId the strategy instance identifier
     */
    protected abstract void onExit(long strategyId);

    /**
     * Exit evaluation hook called on every tick when the strategy is LIVE.
     * <p>
     * Subclasses should:
     * <ol>
     *   <li>Look up the active signal ID for the given strategy.</li>
     *   <li>Compute PnL and check stop-loss / target conditions.</li>
     *   <li>Call {@link #shouldExit(long, long)} and, if true,
     *       {@link #onExit(long)}.</li>
     * </ol>
     *
     * @param strategyId the strategy instance identifier
     */
    protected abstract void onExitEvaluation(long strategyId);

    // ── Tick lifecycle — called by scheduler ─────────────────────────────

    /**
     * Called on every scheduler tick. Routes to entry or exit evaluation
     * based on the current strategy status, with CAS-based idempotency
     * guards preventing concurrent processing of the same strategy.
     * <p>
     * Status routing:
     * <ul>
     *   <li>{@code ACTIVE}, {@code EXIT} — entry path</li>
     *   <li>{@code LIVE} — exit evaluation path</li>
     *   <li>All other statuses are silently ignored.</li>
     * </ul>
     *
     * @param strategyId the strategy instance identifier
     * @param status     the current lifecycle status of the strategy
     */
    public final void check(long strategyId, String status) {
        if ("ACTIVE".equals(status) || "EXIT".equals(status)) {
            if (entryInProgress.putIfAbsent(strategyId, Boolean.TRUE) != null) {
                return; // already processing entry for this strategy
            }
            try {
                onEntry(strategyId);
            } finally {
                entryInProgress.remove(strategyId);
            }
        } else if ("LIVE".equals(status)) {
            if (exitInProgress.putIfAbsent(strategyId, Boolean.TRUE) != null) {
                return; // already processing exit evaluation for this strategy
            }
            try {
                onExitEvaluation(strategyId);
            } finally {
                exitInProgress.remove(strategyId);
            }
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────

    /**
     * Returns the immutable configuration for this strategy instance.
     */
    public StrategyConfig getConfig() {
        return config;
    }
}
