package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.*;

import java.util.ArrayList;
import java.util.List;

/**
 * NiftyGridStraddle -- Rolling 10-leg straddle grid for NIFTY.
 *
 * <h3>Overview:</h3>
 * <p>Enters a 10-leg SELL straddle grid centred at ATM, spanning +/-2 strikes.
 * Each offset creates a CE SELL + PE SELL pair. The grid uses a rolling
 * adjustment: when spot moves more than one strike interval away from the
 * current centre, the two farthest legs on the opposite side are exited and
 * two new legs are added on the movement side (collar exit pattern).</p>
 *
 * <h3>Leg Offsets from ATM:</h3>
 * <pre>
 *   {0, 0, -1, -1, -2, -2, +1, +1, +2, +2}
 *   Each offset produces CE SELL + PE SELL at (ATM + offset * interval)
 * </pre>
 *
 * <h3>Rolling Adjustment (collarExit):</h3>
 * <ul>
 *   <li>Monitor spot vs current centre strike</li>
 *   <li>When |spot - centre| > strikeInterval: exit 2 farthest opposite legs,
 *       enter 2 new legs on the movement side</li>
 * </ul>
 *
 * <h3>Exit Priority:</h3>
 * <ol>
 *   <li>Manual exit flag</li>
 *   <li>PNL stop-loss (configurable)</li>
 *   <li>Time-based (market close)</li>
 *   <li>ATM adjustment (rebalance, not full exit)</li>
 * </ol>
 */
public class NiftyGridStraddle extends BaseStrategy {

    private static final String UNDERLYING = "NIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "currentWeek";
    private static final int MAX_RE_ENTRIES = 5;
    private static final int COOLDOWN_SECONDS = 60;
    private static final double DEFAULT_SL = 10000.0;

    // ── Grid offsets: each element produces CE+PE pair ──────────────────
    private static final int[] LEG_OFFSETS = {0, 0, -1, -1, -2, -2, 1, 1, 2, 2};
    private static final int TOTAL_PAIRS = LEG_OFFSETS.length;
    private static final int LEGS_TO_ROLL = 2;

    // ── Runtime state ──────────────────────────────────────────────────
    private Signal activeSignal;
    private int centreStrike;
    private boolean manualExitRequested;

    public NiftyGridStraddle(OrderService orderService, MarketDataProvider marketData) {
        super(orderService, marketData, StrategyConfig.builder()
                .underlying(UNDERLYING)
                .segment(SEGMENT)
                .strikeInterval(STRIKE_INTERVAL)
                .tradingExpiry(TRADING_EXPIRY)
                .maxReEntries(MAX_RE_ENTRIES)
                .exitCooldownSeconds(COOLDOWN_SECONDS)
                .defaultStopLoss(DEFAULT_SL)
                .build());
    }

    @Override
    protected void onEntry() {
        double spot = marketData.getSpotPrice(UNDERLYING);
        centreStrike = roundToStrike(spot);

        List<StrategyLeg> legs = new ArrayList<>(TOTAL_PAIRS * 2);
        for (int i = 0; i < TOTAL_PAIRS; i++) {
            int strike = centreStrike + (LEG_OFFSETS[i] * STRIKE_INTERVAL);
            legs.add(buildLeg(strike, "CE", i));
            legs.add(buildLeg(strike, "PE", i + TOTAL_PAIRS));
        }

        activeSignal = Signal.builder()
                .strategyId(0L)
                .status("LIVE")
                .currentAtm(centreStrike)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
        manualExitRequested = false;
        markEntry();
        logger.info("[GridStraddle] Entered grid centre=" + centreStrike
                + " legs=" + legs.size());
    }

    @Override
    protected boolean shouldExit() {
        if (activeSignal == null) return false;
        if (manualExitRequested) return true;

        // PNL stop-loss
        double pnl = activeSignal.getTotalUnrealisedPnl();
        if (pnl < -DEFAULT_SL) {
            logger.info("[GridStraddle] PNL SL triggered, pnl=" + pnl);
            return true;
        }

        // Time-based: market close check is handled by BaseStrategy.tick()
        if (!marketData.isMarketOpen()) {
            return true;
        }

        return false;
    }

    @Override
    protected void onExit() {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            activeSignal = null;
        }
        markExit();
        logger.info("[GridStraddle] Full grid exited");
    }

    @Override
    protected void onExitEvaluation() {
        if (activeSignal == null) return;
        evaluateRollingAdjustment();
    }

    /** Requests a manual exit on the next tick. */
    public void requestManualExit() {
        this.manualExitRequested = true;
    }

    // ── Rolling adjustment (collar exit) ───────────────────────────────

    private void evaluateRollingAdjustment() {
        double spot = marketData.getSpotPrice(UNDERLYING);
        double drift = spot - centreStrike;

        if (Math.abs(drift) <= STRIKE_INTERVAL) {
            return; // within band
        }

        boolean movingUp = drift > 0;
        int newCentre = roundToStrike(spot);

        // Exit 2 farthest legs on opposite side, enter 2 new on movement side
        List<StrategyLeg> openLegs = activeSignal.getLegs();
        List<StrategyLeg> toExit = findFarthestLegs(openLegs, movingUp, LEGS_TO_ROLL);
        for (StrategyLeg leg : toExit) {
            orderService.placeSingleLegExit(activeSignal, leg);
        }

        // New legs on the movement side
        int edgeOffset = movingUp ? 2 : -2;
        int newStrike = newCentre + (edgeOffset * STRIKE_INTERVAL);
        for (int i = 0; i < LEGS_TO_ROLL; i++) {
            StrategyLeg ceLeg = buildLeg(newStrike, "CE", 100 + i);
            StrategyLeg peLeg = buildLeg(newStrike, "PE", 200 + i);
            Signal rollSignal = Signal.builder()
                    .strategyId(0L).status("LIVE")
                    .currentAtm(newCentre).baseIndexPrice(spot)
                    .addLeg(ceLeg).addLeg(peLeg)
                    .build();
            orderService.placeEntryOrders(rollSignal);
        }

        centreStrike = newCentre;
        logger.info("[GridStraddle] Rolled " + (movingUp ? "UP" : "DOWN")
                + " newCentre=" + newCentre);
    }

    private List<StrategyLeg> findFarthestLegs(List<StrategyLeg> legs, boolean movingUp,
                                                int count) {
        List<StrategyLeg> sorted = new ArrayList<>(legs);
        if (movingUp) {
            sorted.sort((a, b) -> Integer.compare(a.getStrike(), b.getStrike()));
        } else {
            sorted.sort((a, b) -> Integer.compare(b.getStrike(), a.getStrike()));
        }
        return sorted.subList(0, Math.min(count, sorted.size()));
    }

    private StrategyLeg buildLeg(int strike, String optionType, int index) {
        String symbol = marketData.resolveSymbol(UNDERLYING, strike, optionType, TRADING_EXPIRY);
        OptionQuote quote = marketData.getOptionQuote(symbol);
        double ltp = (quote != null) ? quote.getLtp() : 0.0;
        return StrategyLeg.builder()
                .name(symbol)
                .optionType(optionType)
                .side("SELL")
                .strike(strike)
                .quantity(1)
                .entryPrice(ltp)
                .currentPrice(ltp)
                .status("OPEN")
                .build();
    }
}
