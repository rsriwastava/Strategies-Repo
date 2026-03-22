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
 * <h2>SensexIronFortress &mdash; Iron Condor on SENSEX</h2>
 *
 * <p>An iron condor is a neutral, premium-selling strategy that profits
 * from range-bound markets. It combines a bear call spread and a bull put
 * spread with wider separation between short and long strikes.</p>
 *
 * <h3>Leg construction</h3>
 * <ul>
 *   <li><b>Leg 1</b> &ndash; Sell 1 CE at ATM + 400 (short offset = 2 x 200)</li>
 *   <li><b>Leg 2</b> &ndash; Sell 1 PE at ATM - 400 (short offset = 2 x 200)</li>
 *   <li><b>Leg 3</b> &ndash; Buy 1 CE at ATM + 800 (long offset = 4 x 200)</li>
 *   <li><b>Leg 4</b> &ndash; Buy 1 PE at ATM - 800 (long offset = 4 x 200)</li>
 * </ul>
 *
 * <h3>Risk profile</h3>
 * <ul>
 *   <li>Max gain is the total premium collected, realised when the index
 *       settles between the two short strikes at expiry.</li>
 *   <li>Max loss per side is the wing width (400 pts) minus net premium,
 *       realised when the index breaches either long strike.</li>
 * </ul>
 *
 * @author QuantLab
 * @see BaseStrategy
 */
public class SensexIronFortress extends BaseStrategy {

    // ── Strike & structure constants ────────────────────────────────────
    private static final int STRIKE_INTERVAL = 200;
    private static final int SHORT_OFFSET_MULTIPLIER = 2;
    private static final int LONG_OFFSET_MULTIPLIER = 4;
    private static final int SHORT_OFFSET = SHORT_OFFSET_MULTIPLIER * STRIKE_INTERVAL; // 400
    private static final int LONG_OFFSET = LONG_OFFSET_MULTIPLIER * STRIKE_INTERVAL;   // 800

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
     * Constructs a new {@code SensexIronFortress} strategy.
     *
     * @param orderService       broker-agnostic order execution service
     * @param marketDataProvider market data feed provider
     */
    public SensexIronFortress(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
    }

    // ── Configuration builder ───────────────────────────────────────────

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .strategyName("SensexIronFortress")
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
     * <p>Places the iron condor legs:
     * <ol>
     *   <li>Sells 1 CE at ATM + {@value #SHORT_OFFSET}</li>
     *   <li>Sells 1 PE at ATM - {@value #SHORT_OFFSET}</li>
     *   <li>Buys 1 CE at ATM + {@value #LONG_OFFSET}</li>
     *   <li>Buys 1 PE at ATM - {@value #LONG_OFFSET}</li>
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
        int shortCeStrike = atmStrike + SHORT_OFFSET;
        int shortPeStrike = atmStrike - SHORT_OFFSET;
        int longCeStrike = atmStrike + LONG_OFFSET;
        int longPeStrike = atmStrike - LONG_OFFSET;
        String expiry = marketDataProvider.getExpiry(INDEX, EXPIRY_POLICY);

        Leg shortCe = Leg.builder()
                .strike(shortCeStrike).optionType(OptionType.CE).side(OrderSide.SELL)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Leg shortPe = Leg.builder()
                .strike(shortPeStrike).optionType(OptionType.PE).side(OrderSide.SELL)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Leg longCe = Leg.builder()
                .strike(longCeStrike).optionType(OptionType.CE).side(OrderSide.BUY)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Leg longPe = Leg.builder()
                .strike(longPeStrike).optionType(OptionType.PE).side(OrderSide.BUY)
                .lots(LOTS_PER_LEG).expiry(expiry).segment(SEGMENT).exchange(EXCHANGE).build();

        Signal signal = Signal.builder()
                .strategyName(getConfig().getStrategyName())
                .legs(List.of(shortCe, shortPe, longCe, longPe))
                .build();

        orderService.placeOrder(signal);
        log("Entry placed — Iron Condor: short {}/{}, long {}/{}", shortCeStrike, shortPeStrike, longCeStrike, longPeStrike);
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
