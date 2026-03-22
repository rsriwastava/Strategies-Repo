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
 * <h2>SensexPutVortex &mdash; Put Backspread on SENSEX</h2>
 *
 * <p>A put backspread is a net-debit volatility strategy that profits from
 * sharp downside moves. It sells one ATM put and buys two OTM puts at a
 * lower strike, creating an asymmetric payoff that is capped on the
 * upside but expands significantly on the downside.</p>
 *
 * <h3>Leg construction</h3>
 * <ul>
 *   <li><b>Leg 1</b> &ndash; Sell 1 ATM PE</li>
 *   <li><b>Leg 2</b> &ndash; Buy 2 OTM PE (ATM - 400)</li>
 * </ul>
 *
 * <h3>Risk profile</h3>
 * <ul>
 *   <li>Max loss is limited to the net debit paid plus the strike
 *       difference, realised when the index settles between the two
 *       strikes at expiry.</li>
 *   <li>Max gain is theoretically unlimited on the downside (to zero).</li>
 * </ul>
 *
 * @author QuantLab
 * @see BaseStrategy
 */
public class SensexPutVortex extends BaseStrategy {

    // ── Strike & structure constants ────────────────────────────────────
    private static final int STRIKE_INTERVAL = 200;
    private static final int OTM_OFFSET_MULTIPLIER = 2;
    private static final int OTM_OFFSET = OTM_OFFSET_MULTIPLIER * STRIKE_INTERVAL; // 400

    // ── Lot ratios ──────────────────────────────────────────────────────
    private static final int SHORT_PE_LOTS = 1;
    private static final int LONG_PE_LOTS = 2;

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
     * Constructs a new {@code SensexPutVortex} strategy.
     *
     * @param orderService       broker-agnostic order execution service
     * @param marketDataProvider market data feed provider
     */
    public SensexPutVortex(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
    }

    // ── Configuration builder ───────────────────────────────────────────

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .strategyName("SensexPutVortex")
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
     * <p>Places the put-backspread legs:
     * <ol>
     *   <li>Sells {@value #SHORT_PE_LOTS} lot(s) of ATM PE</li>
     *   <li>Buys {@value #LONG_PE_LOTS} lot(s) of OTM PE at ATM - {@value #OTM_OFFSET}</li>
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
        int otmStrike = atmStrike - OTM_OFFSET;
        String expiry = marketDataProvider.getExpiry(INDEX, EXPIRY_POLICY);

        Leg shortPe = Leg.builder()
                .strike(atmStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.SELL)
                .lots(SHORT_PE_LOTS)
                .expiry(expiry)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        Leg longPe = Leg.builder()
                .strike(otmStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.BUY)
                .lots(LONG_PE_LOTS)
                .expiry(expiry)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        Signal signal = Signal.builder()
                .strategyName(getConfig().getStrategyName())
                .legs(List.of(shortPe, longPe))
                .build();

        orderService.placeOrder(signal);
        log("Entry placed — Sell {}x {} PE, Buy {}x {} PE", SHORT_PE_LOTS, atmStrike, LONG_PE_LOTS, otmStrike);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks whether the combined PNL has breached target or stop-loss
     * thresholds, or if the current time (IST) has passed the scheduled
     * time-based exit.</p>
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

    /**
     * {@inheritDoc}
     *
     * <p>Squares off all open legs by reversing positions through the
     * {@link OrderService}.</p>
     */
    @Override
    public void onExit() {
        orderService.squareOffAll(getConfig().getStrategyName());
        log("All legs squared off for {}", getConfig().getStrategyName());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Rounds a spot price to the nearest strike boundary.
     *
     * @param spot current spot price
     * @return nearest valid strike
     */
    private int roundToStrike(double spot) {
        return (int) (Math.round(spot / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
