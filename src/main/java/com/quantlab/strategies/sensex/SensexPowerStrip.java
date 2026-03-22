package com.quantlab.strategies.sensex;

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
 * <h2>SensexPowerStrip &mdash; Strip on SENSEX</h2>
 *
 * <p>A strip is a volatility strategy with a bearish bias. It buys one ATM
 * call and two ATM puts, resulting in a CE:PE ratio of 1:2. This creates
 * a payoff that accelerates faster on the downside than on the upside,
 * while still profiting from large upside moves.</p>
 *
 * <h3>Leg construction</h3>
 * <ul>
 *   <li><b>Leg 1</b> &ndash; Buy 1 ATM CE</li>
 *   <li><b>Leg 2</b> &ndash; Buy 2 ATM PE</li>
 * </ul>
 *
 * <h3>Risk profile</h3>
 * <ul>
 *   <li>Max loss is the total premium paid (one call + both puts),
 *       realised when the index closes exactly at the ATM strike.</li>
 *   <li>Downside gain is unlimited (to zero) with a steeper slope than
 *       the upside owing to the 1:2 call-to-put ratio.</li>
 *   <li>Upside gain is also unlimited but with a shallower slope.</li>
 * </ul>
 *
 * @author QuantLab
 * @see BaseStrategy
 */
public class SensexPowerStrip extends BaseStrategy {

    // ── Strike & structure constants ────────────────────────────────────
    private static final int STRIKE_INTERVAL = 200;

    // ── Lot ratios (CE:PE = 1:2) ────────────────────────────────────────
    private static final int LONG_CE_LOTS = 1;
    private static final int LONG_PE_LOTS = 2;

    // ── Instrument identifiers ──────────────────────────────────────────
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String INDEX = "SENSEX";
    private static final String EXPIRY_POLICY = "nextWeek";

    // ── Risk-management defaults ────────────────────────────────────────
    private static final double TARGET_PERCENT = 100.0;
    private static final double STOP_LOSS_PERCENT = 40.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(15, 10);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Dependencies ────────────────────────────────────────────────────
    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    /**
     * Constructs a new {@code SensexPowerStrip} strategy.
     *
     * @param orderService       broker-agnostic order execution service
     * @param marketDataProvider market data feed provider
     */
    public SensexPowerStrip(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
    }

    // ── Configuration builder ───────────────────────────────────────────

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .strategyName("SensexPowerStrip")
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
     * <p>Places the strip legs:
     * <ol>
     *   <li>Buys {@value #LONG_CE_LOTS} lot(s) of ATM CE</li>
     *   <li>Buys {@value #LONG_PE_LOTS} lot(s) of ATM PE</li>
     * </ol>
     */
    @Override
    public void onEntry() {
        double spotPrice = marketDataProvider.getSpotPrice(INDEX);
        int atmStrike = roundToStrike(spotPrice);
        String expiry = marketDataProvider.getExpiry(INDEX, EXPIRY_POLICY);

        Leg longCe = Leg.builder()
                .strike(atmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .lots(LONG_CE_LOTS)
                .expiry(expiry)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        Leg longPe = Leg.builder()
                .strike(atmStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.BUY)
                .lots(LONG_PE_LOTS)
                .expiry(expiry)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        Signal signal = Signal.builder()
                .strategyName(getConfig().getStrategyName())
                .legs(List.of(longCe, longPe))
                .build();

        orderService.placeOrder(signal);
        log("Entry placed — Buy {}x {} CE + Buy {}x {} PE (Strip 1:2)", LONG_CE_LOTS, atmStrike, LONG_PE_LOTS, atmStrike);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks combined PNL against target/stop-loss and evaluates
     * time-based exit at {@code 15:10 IST}.</p>
     *
     * @return {@code true} if an exit condition is met
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
            log("Time-based exit triggered at {}", TIME_EXIT);
            return true;
        }
        return false;
    }

    /** {@inheritDoc} */
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
