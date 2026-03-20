package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.*;

import java.time.Instant;

/**
 * NiftyPairStrike -- ATM straddle at cheapest strike with mid-trade adjustment (Jodi).
 *
 * <h3>Overview:</h3>
 * <p>Enters a 2-leg straddle (CE SELL + PE SELL) at whichever ATM is cheaper:
 * Spot ATM or Future ATM. During the trade, adjacent strikes (+/- one
 * interval) are continuously monitored. If an adjacent strike's combined
 * straddle premium is lower than the current position's entry premium for
 * N consecutive confirmation seconds, the strategy adjusts by exiting the
 * current position and re-entering at the new strike.</p>
 *
 * <h3>Mid-Trade Adjustment:</h3>
 * <ul>
 *   <li>Adjacent strikes checked: entry +/- strikeInterval</li>
 *   <li>Confirmation window: 5 consecutive ticks (~30s at standard tick rate)</li>
 *   <li>Daily adjustment cap: 3 adjustments per day</li>
 *   <li>Cooldown between adjustments: 120 seconds</li>
 * </ul>
 *
 * <h3>Legs:</h3>
 * <pre>
 *   Leg 1: ATM CE SELL
 *   Leg 2: ATM PE SELL
 * </pre>
 */
public class NiftyPairStrike extends BaseStrategy {

    private static final String UNDERLYING = "NIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "currentWeek";
    private static final int MAX_RE_ENTRIES = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Adjustment parameters ──────────────────────────────────────────
    private static final int ADJUSTMENT_CONFIRMATION_TICKS = 5;
    private static final int MAX_DAILY_ADJUSTMENTS = 3;
    private static final long ADJUSTMENT_COOLDOWN_MILLIS = 120L * 1000;

    // ── Runtime state ──────────────────────────────────────────────────
    private Signal activeSignal;
    private int currentStrike;
    private double entryStraddlePremium;
    private int adjacentConfirmationCount;
    private int confirmedAdjacentStrike;
    private int dailyAdjustmentCount;
    private long lastAdjustmentTimestamp;

    public NiftyPairStrike(OrderService orderService, MarketDataProvider marketData) {
        super(orderService, marketData, StrategyConfig.builder()
                .underlying(UNDERLYING)
                .segment(SEGMENT)
                .strikeInterval(STRIKE_INTERVAL)
                .tradingExpiry(TRADING_EXPIRY)
                .maxReEntries(MAX_RE_ENTRIES)
                .exitCooldownSeconds(COOLDOWN_SECONDS)
                .build());
    }

    @Override
    protected void onEntry() {
        double spot = marketData.getSpotPrice(UNDERLYING);
        int spotAtm = roundToStrike(spot);

        // Compare Spot ATM vs Future ATM straddle premiums
        double spotPremium = getStraddlePremium(spotAtm);

        // Use ATR as a proxy for future price (since we don't have a direct future call)
        double futurePrice = marketData.getATR(UNDERLYING, 0);
        int futureAtm = roundToStrike(futurePrice > 0 ? futurePrice : spot);
        double futurePremium = getStraddlePremium(futureAtm);

        // Pick cheaper straddle
        boolean useSpot = (spotPremium <= futurePremium) || futurePremium <= 0;
        int selectedStrike = useSpot ? spotAtm : futureAtm;
        double selectedPremium = useSpot ? spotPremium : futurePremium;

        if (selectedPremium <= 0) {
            return;
        }

        enterAtStrike(selectedStrike, spot);
    }

    @Override
    protected boolean shouldExit() {
        if (activeSignal == null) return false;
        double pnl = activeSignal.getTotalUnrealisedPnl();
        if (pnl < -config.getDefaultStopLoss() && config.getDefaultStopLoss() > 0) {
            return true;
        }
        return !marketData.isMarketOpen();
    }

    @Override
    protected void onExit() {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            activeSignal = null;
        }
        markExit();
        logger.info("[PairStrike] Exited strike=" + currentStrike);
    }

    @Override
    protected void onExitEvaluation() {
        if (activeSignal == null) return;
        evaluateAdjustment();
    }

    // ── Mid-trade adjustment logic ─────────────────────────────────────

    private void evaluateAdjustment() {
        if (dailyAdjustmentCount >= MAX_DAILY_ADJUSTMENTS) return;
        long now = Instant.now().toEpochMilli();
        if ((now - lastAdjustmentTimestamp) < ADJUSTMENT_COOLDOWN_MILLIS) return;

        // Check adjacent strikes
        int upperStrike = currentStrike + STRIKE_INTERVAL;
        int lowerStrike = currentStrike - STRIKE_INTERVAL;
        double upperPremium = getStraddlePremium(upperStrike);
        double lowerPremium = getStraddlePremium(lowerStrike);

        int cheaperAdjacent = -1;
        double cheaperPremium = entryStraddlePremium;

        if (upperPremium > 0 && upperPremium < cheaperPremium) {
            cheaperAdjacent = upperStrike;
            cheaperPremium = upperPremium;
        }
        if (lowerPremium > 0 && lowerPremium < cheaperPremium) {
            cheaperAdjacent = lowerStrike;
        }

        if (cheaperAdjacent < 0) {
            adjacentConfirmationCount = 0;
            confirmedAdjacentStrike = -1;
            return;
        }

        // Confirmation logic
        if (cheaperAdjacent == confirmedAdjacentStrike) {
            adjacentConfirmationCount++;
        } else {
            confirmedAdjacentStrike = cheaperAdjacent;
            adjacentConfirmationCount = 1;
        }

        if (adjacentConfirmationCount >= ADJUSTMENT_CONFIRMATION_TICKS) {
            logger.info("[PairStrike] Adjusting from " + currentStrike
                    + " to " + confirmedAdjacentStrike);

            // Exit current position
            orderService.placeExitOrders(activeSignal);
            activeSignal = null;

            // Enter at new strike
            double spot = marketData.getSpotPrice(UNDERLYING);
            enterAtStrike(confirmedAdjacentStrike, spot);

            dailyAdjustmentCount++;
            lastAdjustmentTimestamp = Instant.now().toEpochMilli();
            adjacentConfirmationCount = 0;
            confirmedAdjacentStrike = -1;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void enterAtStrike(int strike, double spot) {
        String ceSymbol = marketData.resolveSymbol(UNDERLYING, strike, "CE", TRADING_EXPIRY);
        String peSymbol = marketData.resolveSymbol(UNDERLYING, strike, "PE", TRADING_EXPIRY);
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) return;

        currentStrike = strike;
        entryStraddlePremium = ceQuote.getLtp() + peQuote.getLtp();

        StrategyLeg ceLeg = StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(strike).quantity(1)
                .entryPrice(ceQuote.getLtp()).currentPrice(ceQuote.getLtp())
                .status("OPEN").build();

        StrategyLeg peLeg = StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(strike).quantity(1)
                .entryPrice(peQuote.getLtp()).currentPrice(peQuote.getLtp())
                .status("OPEN").build();

        activeSignal = Signal.builder()
                .strategyId(0L).status("LIVE")
                .currentAtm(strike).baseIndexPrice(spot)
                .addLeg(ceLeg).addLeg(peLeg)
                .build();

        orderService.placeEntryOrders(activeSignal);
        markEntry();
        logger.info("[PairStrike] Entered strike=" + strike
                + " premium=" + entryStraddlePremium);
    }

    private double getStraddlePremium(int strike) {
        String ceS = marketData.resolveSymbol(UNDERLYING, strike, "CE", TRADING_EXPIRY);
        String peS = marketData.resolveSymbol(UNDERLYING, strike, "PE", TRADING_EXPIRY);
        OptionQuote ce = marketData.getOptionQuote(ceS);
        OptionQuote pe = marketData.getOptionQuote(peS);
        if (ce == null || pe == null || ce.getLtp() <= 0 || pe.getLtp() <= 0) {
            return -1.0;
        }
        return ce.getLtp() + pe.getLtp();
    }
}
