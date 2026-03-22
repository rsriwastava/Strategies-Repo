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
 * <h2>SensexBullRiser &mdash; Bull Call Spread on SENSEX</h2>
 *
 * <p>A bull call spread is a moderately bullish, limited-risk strategy that
 * buys an ATM call and simultaneously sells a higher-strike OTM call. The
 * premium received from the short call partially offsets the cost of the
 * long call, reducing the overall debit.</p>
 *
 * <h3>Leg construction</h3>
 * <ul>
 *   <li><b>Leg 1</b> &ndash; Buy 1 ATM CE</li>
 *   <li><b>Leg 2</b> &ndash; Sell 1 OTM CE (ATM + 600)</li>
 * </ul>
 *
 * <h3>Risk profile</h3>
 * <ul>
 *   <li>Max loss is the net debit paid, realised when the index
 *       closes at or below the long strike at expiry.</li>
 *   <li>Max gain is the difference between strikes minus the net debit,
 *       realised when the index closes at or above the short strike.</li>
 * </ul>
 *
 * @author QuantLab
 * @see BaseStrategy
 */
public class SensexBullRiser extends BaseStrategy {

    // ── Strike & structure constants ────────────────────────────────────
    private static final int STRIKE_INTERVAL = 200;
    private static final int OTM_OFFSET_MULTIPLIER = 3;
    private static final int OTM_OFFSET = OTM_OFFSET_MULTIPLIER * STRIKE_INTERVAL; // 600

    // ── Lot ratios ──────────────────────────────────────────────────────
    private static final int LONG_CE_LOTS = 1;
    private static final int SHORT_CE_LOTS = 1;

    // ── Instrument identifiers ──────────────────────────────────────────
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String INDEX = "SENSEX";
    private static final String EXPIRY_POLICY = "nextWeek";

    // ── Risk-management defaults ────────────────────────────────────────
    private static final double TARGET_PERCENT = 60.0;
    private static final double STOP_LOSS_PERCENT = 40.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Dependencies ────────────────────────────────────────────────────
    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    /**
     * Constructs a new {@code SensexBullRiser} strategy.
     *
     * @param orderService       broker-agnostic order execution service
     * @param marketDataProvider market data feed provider
     */
    public SensexBullRiser(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
    }

    // ── Configuration builder ───────────────────────────────────────────

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .strategyName("SensexBullRiser")
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
     * <p>Places the bull call spread legs:
     * <ol>
     *   <li>Buys {@value #LONG_CE_LOTS} lot(s) of ATM CE</li>
     *   <li>Sells {@value #SHORT_CE_LOTS} lot(s) of OTM CE at ATM + {@value #OTM_OFFSET}</li>
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
        int otmStrike = atmStrike + OTM_OFFSET;
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

        Leg shortCe = Leg.builder()
                .strike(otmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.SELL)
                .lots(SHORT_CE_LOTS)
                .expiry(expiry)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        Signal signal = Signal.builder()
                .strategyName(getConfig().getStrategyName())
                .legs(List.of(longCe, shortCe))
                .build();

        orderService.placeOrder(signal);
        log("Entry placed — Buy {}x {} CE, Sell {}x {} CE", LONG_CE_LOTS, atmStrike, SHORT_CE_LOTS, otmStrike);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Evaluates PNL percentage against target and stop-loss thresholds
     * and checks for a time-based exit past {@code 15:10 IST}.</p>
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
     * <p>Squares off all open legs through the {@link OrderService}.</p>
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
