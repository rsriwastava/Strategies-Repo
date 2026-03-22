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
 * <h2>SensexCallAscent &mdash; Call Ladder on SENSEX</h2>
 *
 * <p>A call ladder (also known as a long call ladder or bull call ladder)
 * is a three-legged strategy that starts as a bull call spread but adds
 * an additional short call at a higher strike. The extra short leg
 * collects premium to reduce cost but introduces unlimited upside risk
 * beyond the highest strike.</p>
 *
 * <h3>Leg construction</h3>
 * <ul>
 *   <li><b>Leg 1</b> &ndash; Buy 1 ATM CE</li>
 *   <li><b>Leg 2</b> &ndash; Sell 1 OTM CE (ATM + 400)</li>
 *   <li><b>Leg 3</b> &ndash; Sell 1 far OTM CE (ATM + 800)</li>
 * </ul>
 *
 * <h3>Risk profile</h3>
 * <ul>
 *   <li>Max gain is achieved when the index settles between the two
 *       short strikes at expiry.</li>
 *   <li>Downside risk is limited to the net debit paid.</li>
 *   <li>Upside risk is theoretically unlimited beyond the highest short
 *       strike (net short one call above ATM + 800).</li>
 * </ul>
 *
 * @author QuantLab
 * @see BaseStrategy
 */
public class SensexCallAscent extends BaseStrategy {

    // ── Strike & structure constants ────────────────────────────────────
    private static final int STRIKE_INTERVAL = 200;
    private static final int FIRST_OTM_OFFSET = 2 * STRIKE_INTERVAL;  // 400
    private static final int SECOND_OTM_OFFSET = 4 * STRIKE_INTERVAL; // 800

    // ── Lot sizes ───────────────────────────────────────────────────────
    private static final int LOTS_PER_LEG = 1;

    // ── Instrument identifiers ──────────────────────────────────────────
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String INDEX = "SENSEX";
    private static final String EXPIRY_POLICY = "nextWeek";

    // ── Risk-management defaults ────────────────────────────────────────
    private static final double TARGET_PERCENT = 70.0;
    private static final double STOP_LOSS_PERCENT = 35.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(15, 10);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Dependencies ────────────────────────────────────────────────────
    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    /**
     * Constructs a new {@code SensexCallAscent} strategy.
     *
     * @param orderService       broker-agnostic order execution service
     * @param marketDataProvider market data feed provider
     */
    public SensexCallAscent(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
    }

    // ── Configuration builder ───────────────────────────────────────────

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .strategyName("SensexCallAscent")
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
     * <p>Places the call ladder legs:
     * <ol>
     *   <li>Buys 1 ATM CE</li>
     *   <li>Sells 1 OTM CE at ATM + {@value #FIRST_OTM_OFFSET}</li>
     *   <li>Sells 1 far OTM CE at ATM + {@value #SECOND_OTM_OFFSET}</li>
     * </ol>
     */
    @Override
    public void onEntry() {
        double spotPrice = marketDataProvider.getSpotPrice(INDEX);
        int atmStrike = roundToStrike(spotPrice);
        int firstOtm = atmStrike + FIRST_OTM_OFFSET;
        int secondOtm = atmStrike + SECOND_OTM_OFFSET;
        String expiry = marketDataProvider.getExpiry(INDEX, EXPIRY_POLICY);

        Leg longCe = Leg.builder()
                .strike(atmStrike).optionType(OptionType.CE).side(OrderSide.BUY)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Leg shortCe1 = Leg.builder()
                .strike(firstOtm).optionType(OptionType.CE).side(OrderSide.SELL)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Leg shortCe2 = Leg.builder()
                .strike(secondOtm).optionType(OptionType.CE).side(OrderSide.SELL)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Signal signal = Signal.builder()
                .strategyName(getConfig().getStrategyName())
                .legs(List.of(longCe, shortCe1, shortCe2))
                .build();

        orderService.placeOrder(signal);
        log("Entry placed — Call Ladder: Buy {} CE, Sell {} CE, Sell {} CE", atmStrike, firstOtm, secondOtm);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} if target, stop-loss, or time exit is triggered
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
