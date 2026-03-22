package com.quantlab.strategies.popular.sensex;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.model.Leg;
import com.quantlab.strategies.core.model.OptionType;
import com.quantlab.strategies.core.model.OrderSide;
import com.quantlab.strategies.core.model.Signal;
import com.quantlab.strategies.core.service.MarketDataProvider;
import com.quantlab.strategies.core.service.OrderService;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * <h2>SensexVolBurst &mdash; Long Straddle on SENSEX</h2>
 *
 * <p>A long straddle is a pure volatility strategy that buys both an ATM
 * call and an ATM put at the same strike. It profits from large moves in
 * either direction, making it ideal for high-volatility events such as
 * budget announcements, RBI policy days, or earnings-driven gaps.</p>
 *
 * <h3>Leg construction</h3>
 * <ul>
 *   <li><b>Leg 1</b> &ndash; Buy 1 ATM CE</li>
 *   <li><b>Leg 2</b> &ndash; Buy 1 ATM PE</li>
 * </ul>
 *
 * <h3>Risk profile</h3>
 * <ul>
 *   <li>Max loss is the total premium paid (both legs), realised when the
 *       index closes exactly at the ATM strike at expiry.</li>
 *   <li>Max gain is theoretically unlimited in either direction.</li>
 * </ul>
 *
 * <h3>Exit rules</h3>
 * <ul>
 *   <li>Target: {@value #TARGET_PERCENT}% of premium paid</li>
 *   <li>Stop-loss: {@value #STOP_LOSS_PERCENT}% of premium paid</li>
 *   <li>Time exit: {@code 13:00 IST} (early exit to avoid theta decay)</li>
 * </ul>
 *
 * @author QuantLab
 * @see BaseStrategy
 */
public class SensexVolBurst extends BaseStrategy {

    // ── Strike & structure constants ────────────────────────────────────
    private static final int STRIKE_INTERVAL = 200;

    // ── Lot sizes ───────────────────────────────────────────────────────
    private static final int LOTS_PER_LEG = 1;

    // ── Instrument identifiers ──────────────────────────────────────────
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String INDEX = "SENSEX";
    private static final String EXPIRY_POLICY = "nextWeek";

    // ── Risk-management defaults ────────────────────────────────────────
    private static final double TARGET_PERCENT = 80.0;
    private static final double STOP_LOSS_PERCENT = 35.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Dependencies ────────────────────────────────────────────────────
    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    /**
     * Constructs a new {@code SensexVolBurst} strategy.
     *
     * @param orderService       broker-agnostic order execution service
     * @param marketDataProvider market data feed provider
     */
    public SensexVolBurst(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
    }

    // ── Configuration builder ───────────────────────────────────────────

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .strategyName("SensexVolBurst")
                .index(INDEX)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .strikeInterval(STRIKE_INTERVAL)
                .expiryPolicy(EXPIRY_POLICY)
                .targetPercent(TARGET_PERCENT)
                .stopLossPercent(STOP_LOSS_PERCENT)
                .timeExit(TIME_EXIT)
                .build();
    }

    // ── Strategy lifecycle ──────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Places the long straddle legs:
     * <ol>
     *   <li>Buys {@value #LOTS_PER_LEG} lot(s) of ATM CE</li>
     *   <li>Buys {@value #LOTS_PER_LEG} lot(s) of ATM PE</li>
     * </ol>
     */
    @Override
    public void onEntry() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(ENTRY_TIME)) {
            log("[{}] Entry blocked — before {} IST", getConfig().getStrategyName(), ENTRY_TIME);
            return;
        }

        double spotPrice = marketDataProvider.getSpotPrice(INDEX);
        int atmStrike = roundToStrike(spotPrice);
        String expiry = marketDataProvider.getExpiry(INDEX, EXPIRY_POLICY);

        Leg longCe = Leg.builder()
                .strike(atmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .lots(LOTS_PER_LEG)
                .expiry(expiry)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        Leg longPe = Leg.builder()
                .strike(atmStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.BUY)
                .lots(LOTS_PER_LEG)
                .expiry(expiry)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        Signal signal = Signal.builder()
                .strategyName(getConfig().getStrategyName())
                .legs(List.of(longCe, longPe))
                .build();

        orderService.placeOrder(signal);
        log("Entry placed — Long Straddle at ATM {}", atmStrike);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Evaluates three exit conditions in order:</p>
     * <ol>
     *   <li>PNL target of {@value #TARGET_PERCENT}%</li>
     *   <li>PNL stop-loss of {@value #STOP_LOSS_PERCENT}%</li>
     *   <li>Time-based exit at {@code 13:00 IST} to limit theta bleed</li>
     * </ol>
     *
     * @return {@code true} if any exit condition is met
     */
    @Override
    public boolean shouldExit() {
        double pnlPercent = getPnlPercent();
        if (pnlPercent >= TARGET_PERCENT) {
            log("Target of {}% hit — current PNL {}%", TARGET_PERCENT, pnlPercent);
            return true;
        }
        if (pnlPercent <= -STOP_LOSS_PERCENT) {
            log("Stop-loss of {}% breached — current PNL {}%", STOP_LOSS_PERCENT, pnlPercent);
            return true;
        }
        if (LocalTime.now(IST).isAfter(TIME_EXIT)) {
            log("Time-based exit triggered at {} IST to limit theta decay", TIME_EXIT);
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Squares off both legs through the {@link OrderService}.</p>
     */
    @Override
    public void onExit() {
        orderService.squareOffAll(getConfig().getStrategyName());
        log("All legs squared off for {}", getConfig().getStrategyName());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private int roundToStrike(double spot) {
        return (int) (Math.round(spot / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
