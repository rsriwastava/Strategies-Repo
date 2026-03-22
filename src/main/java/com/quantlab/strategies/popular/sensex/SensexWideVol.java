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
 * <h2>SensexWideVol &mdash; Long Strangle on SENSEX</h2>
 *
 * <p>A long strangle is a volatility strategy that buys an OTM call and an
 * OTM put equidistant from the ATM strike. Compared to a straddle it has
 * a lower premium outlay but requires a larger move to become profitable,
 * making it suitable for anticipated high-volatility events where the
 * direction is uncertain.</p>
 *
 * <h3>Leg construction</h3>
 * <ul>
 *   <li><b>Leg 1</b> &ndash; Buy 1 OTM CE (ATM + 400)</li>
 *   <li><b>Leg 2</b> &ndash; Buy 1 OTM PE (ATM - 400)</li>
 * </ul>
 *
 * <h3>Risk profile</h3>
 * <ul>
 *   <li>Max loss is the total premium paid (both legs), realised when the
 *       index closes between the two strikes at expiry.</li>
 *   <li>Max gain is theoretically unlimited in either direction once the
 *       index moves beyond a strike by more than the total premium.</li>
 * </ul>
 *
 * <h3>Exit rules</h3>
 * <ul>
 *   <li>Target: {@value #TARGET_PERCENT}% of premium paid</li>
 *   <li>Stop-loss: {@value #STOP_LOSS_PERCENT}% of premium paid</li>
 *   <li>Time exit: {@code 15:10 IST}</li>
 * </ul>
 *
 * @author QuantLab
 * @see BaseStrategy
 */
public class SensexWideVol extends BaseStrategy {

    // ── Strike & structure constants ────────────────────────────────────
    private static final int STRIKE_INTERVAL = 200;
    private static final int OTM_OFFSET_MULTIPLIER = 2;
    private static final int OTM_OFFSET = OTM_OFFSET_MULTIPLIER * STRIKE_INTERVAL; // 400

    // ── Lot sizes ───────────────────────────────────────────────────────
    private static final int LOTS_PER_LEG = 1;

    // ── Instrument identifiers ──────────────────────────────────────────
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String INDEX = "SENSEX";
    private static final String EXPIRY_POLICY = "nextWeek";

    // ── Risk-management defaults ────────────────────────────────────────
    private static final double TARGET_PERCENT = 120.0;
    private static final double STOP_LOSS_PERCENT = 40.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Dependencies ────────────────────────────────────────────────────
    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    /**
     * Constructs a new {@code SensexWideVol} strategy.
     *
     * @param orderService       broker-agnostic order execution service
     * @param marketDataProvider market data feed provider
     */
    public SensexWideVol(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
    }

    // ── Configuration builder ───────────────────────────────────────────

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .strategyName("SensexWideVol")
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
     * <p>Places the long strangle legs:
     * <ol>
     *   <li>Buys {@value #LOTS_PER_LEG} lot(s) of OTM CE at ATM + {@value #OTM_OFFSET}</li>
     *   <li>Buys {@value #LOTS_PER_LEG} lot(s) of OTM PE at ATM - {@value #OTM_OFFSET}</li>
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
        int otmCeStrike = atmStrike + OTM_OFFSET;
        int otmPeStrike = atmStrike - OTM_OFFSET;
        String expiry = marketDataProvider.getExpiry(INDEX, EXPIRY_POLICY);

        Leg longCe = Leg.builder()
                .strike(otmCeStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .lots(LOTS_PER_LEG)
                .expiry(expiry)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        Leg longPe = Leg.builder()
                .strike(otmPeStrike)
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
        log("Entry placed — Long Strangle: Buy {} CE + Buy {} PE", otmCeStrike, otmPeStrike);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Evaluates three exit conditions in order:</p>
     * <ol>
     *   <li>PNL target of {@value #TARGET_PERCENT}%</li>
     *   <li>PNL stop-loss of {@value #STOP_LOSS_PERCENT}%</li>
     *   <li>Time-based exit at {@code 15:10 IST}</li>
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
            log("Time-based exit triggered at {}", TIME_EXIT);
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
