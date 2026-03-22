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
 * <h2>SensexIronWing &mdash; Iron Butterfly on SENSEX</h2>
 *
 * <p>An iron butterfly is a neutral, premium-selling strategy that combines
 * a short straddle with protective wings. It profits from low volatility
 * when the index pins near the ATM strike at expiry.</p>
 *
 * <h3>Leg construction</h3>
 * <ul>
 *   <li><b>Leg 1</b> &ndash; Sell 1 ATM CE</li>
 *   <li><b>Leg 2</b> &ndash; Sell 1 ATM PE</li>
 *   <li><b>Leg 3</b> &ndash; Buy 1 OTM CE (ATM + 400)</li>
 *   <li><b>Leg 4</b> &ndash; Buy 1 OTM PE (ATM - 400)</li>
 * </ul>
 *
 * <h3>Risk profile</h3>
 * <ul>
 *   <li>Max gain is the total premium collected minus the premium paid
 *       for the wings, realised when the index closes exactly at ATM.</li>
 *   <li>Max loss is the wing width minus net premium, realised when
 *       the index breaches either wing strike.</li>
 * </ul>
 *
 * @author QuantLab
 * @see BaseStrategy
 */
public class SensexIronWing extends BaseStrategy {

    // ── Strike & structure constants ────────────────────────────────────
    private static final int STRIKE_INTERVAL = 200;
    private static final int WING_OFFSET_MULTIPLIER = 2;
    private static final int WING_WIDTH = WING_OFFSET_MULTIPLIER * STRIKE_INTERVAL; // 400

    // ── Lot sizes ───────────────────────────────────────────────────────
    private static final int LOTS_PER_LEG = 1;

    // ── Instrument identifiers ──────────────────────────────────────────
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String INDEX = "SENSEX";
    private static final String EXPIRY_POLICY = "nextWeek";

    // ── Risk-management defaults ────────────────────────────────────────
    private static final double TARGET_PERCENT = 50.0;
    private static final double STOP_LOSS_PERCENT = 50.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Dependencies ────────────────────────────────────────────────────
    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    /**
     * Constructs a new {@code SensexIronWing} strategy.
     *
     * @param orderService       broker-agnostic order execution service
     * @param marketDataProvider market data feed provider
     */
    public SensexIronWing(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
    }

    // ── Configuration builder ───────────────────────────────────────────

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .strategyName("SensexIronWing")
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
     * <p>Places the iron butterfly legs:
     * <ol>
     *   <li>Sells 1 ATM CE</li>
     *   <li>Sells 1 ATM PE</li>
     *   <li>Buys 1 OTM CE at ATM + {@value #WING_WIDTH}</li>
     *   <li>Buys 1 OTM PE at ATM - {@value #WING_WIDTH}</li>
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
        int upperWing = atmStrike + WING_WIDTH;
        int lowerWing = atmStrike - WING_WIDTH;
        String expiry = marketDataProvider.getExpiry(INDEX, EXPIRY_POLICY);

        Leg shortCe = Leg.builder()
                .strike(atmStrike).optionType(OptionType.CE).side(OrderSide.SELL)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Leg shortPe = Leg.builder()
                .strike(atmStrike).optionType(OptionType.PE).side(OrderSide.SELL)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Leg longCe = Leg.builder()
                .strike(upperWing).optionType(OptionType.CE).side(OrderSide.BUY)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Leg longPe = Leg.builder()
                .strike(lowerWing).optionType(OptionType.PE).side(OrderSide.BUY)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Signal signal = Signal.builder()
                .strategyName(getConfig().getStrategyName())
                .legs(List.of(shortCe, shortPe, longCe, longPe))
                .build();

        orderService.placeOrder(signal);
        log("Entry placed — Iron Butterfly around ATM {} with wings at {}/{}", atmStrike, lowerWing, upperWing);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks PNL percentage against target/stop-loss and evaluates
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

    /**
     * {@inheritDoc}
     *
     * <p>Squares off all four legs through the {@link OrderService}.</p>
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
