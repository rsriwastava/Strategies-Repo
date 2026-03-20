package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.*;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * NiftyShieldedNeutral -- 4-leg hedged delta-neutral straddle for NIFTY.
 *
 * <h3>Overview:</h3>
 * <p>Same core as DeltaZero (delta-balanced CE SELL + PE SELL at ATM) but adds
 * two OTM BUY hedge legs (one CE, one PE). This creates a limited-risk
 * straddle position where the bought wings cap the maximum loss.</p>
 *
 * <h3>Hedge Strike Selection (DTE-scaled premium):</h3>
 * <pre>
 *   DTE >= 4  -> hedge at strike where premium ~= 10% of short premium
 *   DTE 2-3   -> hedge at strike where premium ~= 15% of short premium
 *   DTE 0-1   -> hedge at strike where premium ~= 22% of short premium
 * </pre>
 *
 * <h3>Hedge Lots:</h3>
 * <p>Standard assignment -- CE hedge lots match CE SELL lots, PE hedge lots
 * match PE SELL lots. This ensures each wing is fully covered.</p>
 *
 * <h3>Legs:</h3>
 * <pre>
 *   Leg 1: ATM CE SELL (delta-balanced lots)
 *   Leg 2: ATM PE SELL (delta-balanced lots)
 *   Leg 3: OTM CE BUY (same lots as CE SELL)
 *   Leg 4: OTM PE BUY (same lots as PE SELL)
 * </pre>
 *
 * <h3>Exit:</h3>
 * <p>PNL-based, time-based, or manual -- same as DeltaZero.</p>
 */
public class NiftyShieldedNeutral extends BaseStrategy {

    private static final String UNDERLYING = "NIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "currentWeek";
    private static final int MAX_RE_ENTRIES = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Delta balance tiers ────────────────────────────────────────────
    private static final double DELTA_BALANCE_BAND = 0.05;
    private static final double DELTA_MILD_UPPER = 0.12;

    // ── Hedge premium ratios by DTE ────────────────────────────────────
    private static final double HEDGE_RATIO_DTE_HIGH = 0.10;
    private static final double HEDGE_RATIO_DTE_MID = 0.15;
    private static final double HEDGE_RATIO_DTE_LOW = 0.22;
    private static final int DTE_HIGH_THRESHOLD = 4;
    private static final int DTE_MID_THRESHOLD = 2;

    // ── Entry cutoff ───────────────────────────────────────────────────
    private static final int ENTRY_CUTOFF_HOUR = 14;
    private static final int ENTRY_CUTOFF_MINUTE = 30;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Runtime state ──────────────────────────────────────────────────
    private Signal activeSignal;
    private boolean manualExitRequested;

    public NiftyShieldedNeutral(OrderService orderService, MarketDataProvider marketData) {
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
        LocalTime now = LocalTime.now(IST);
        if (now.isAfter(LocalTime.of(ENTRY_CUTOFF_HOUR, ENTRY_CUTOFF_MINUTE))) {
            return;
        }

        double spot = marketData.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrike(spot);

        String ceSymbol = marketData.resolveSymbol(UNDERLYING, atmStrike, "CE", TRADING_EXPIRY);
        String peSymbol = marketData.resolveSymbol(UNDERLYING, atmStrike, "PE", TRADING_EXPIRY);
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) return;

        // ── Delta-balanced lots ────────────────────────────────────────
        double callDelta = Math.abs(ceQuote.getDelta());
        double putDelta = Math.abs(peQuote.getDelta());
        int[] lots = computeBalancedLots(callDelta, putDelta);

        // ── Hedge strike selection ─────────────────────────────────────
        int dte = marketData.getDTE(UNDERLYING, TRADING_EXPIRY);
        double hedgeRatio = getHedgePremiumRatio(dte);
        double targetCeHedgePremium = ceQuote.getLtp() * hedgeRatio;
        double targetPeHedgePremium = peQuote.getLtp() * hedgeRatio;
        int ceHedgeStrike = findHedgeStrike(atmStrike, "CE", targetCeHedgePremium);
        int peHedgeStrike = findHedgeStrike(atmStrike, "PE", targetPeHedgePremium);

        String ceHedgeSymbol = marketData.resolveSymbol(UNDERLYING, ceHedgeStrike, "CE", TRADING_EXPIRY);
        String peHedgeSymbol = marketData.resolveSymbol(UNDERLYING, peHedgeStrike, "PE", TRADING_EXPIRY);
        OptionQuote ceHedgeQuote = marketData.getOptionQuote(ceHedgeSymbol);
        OptionQuote peHedgeQuote = marketData.getOptionQuote(peHedgeSymbol);
        if (ceHedgeQuote == null || peHedgeQuote == null) return;

        // ── Build 4-leg signal ─────────────────────────────────────────
        StrategyLeg ceSell = buildLeg(ceSymbol, "CE", "SELL", atmStrike, lots[0], ceQuote.getLtp());
        StrategyLeg peSell = buildLeg(peSymbol, "PE", "SELL", atmStrike, lots[1], peQuote.getLtp());
        // Standard assignment: CE hedge matches CE SELL lots, PE hedge matches PE SELL lots
        StrategyLeg ceBuy = buildLeg(ceHedgeSymbol, "CE", "BUY", ceHedgeStrike, lots[0], ceHedgeQuote.getLtp());
        StrategyLeg peBuy = buildLeg(peHedgeSymbol, "PE", "BUY", peHedgeStrike, lots[1], peHedgeQuote.getLtp());

        activeSignal = Signal.builder()
                .strategyId(0L).status("LIVE")
                .currentAtm(atmStrike).baseIndexPrice(spot)
                .addLeg(ceSell).addLeg(peSell)
                .addLeg(ceBuy).addLeg(peBuy)
                .build();

        orderService.placeEntryOrders(activeSignal);
        manualExitRequested = false;
        markEntry();
        logger.info("[ShieldedNeutral] Entry atm=" + atmStrike
                + " ceHedge=" + ceHedgeStrike + " peHedge=" + peHedgeStrike
                + " ceLots=" + lots[0] + " peLots=" + lots[1]);
    }

    @Override
    protected boolean shouldExit() {
        if (activeSignal == null) return false;
        if (manualExitRequested) return true;

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
        logger.info("[ShieldedNeutral] Position exited");
    }

    @Override
    protected void onExitEvaluation() {
        // Delta drift monitoring (similar to DeltaZero)
    }

    public void requestManualExit() {
        this.manualExitRequested = true;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private int[] computeBalancedLots(double callDelta, double putDelta) {
        double diff = Math.abs(callDelta - putDelta);
        if (diff <= DELTA_BALANCE_BAND) return new int[]{1, 1};
        if (diff <= DELTA_MILD_UPPER) {
            return callDelta > putDelta ? new int[]{2, 3} : new int[]{3, 2};
        }
        return callDelta > putDelta ? new int[]{1, 2} : new int[]{2, 1};
    }

    private double getHedgePremiumRatio(int dte) {
        if (dte >= DTE_HIGH_THRESHOLD) return HEDGE_RATIO_DTE_HIGH;
        if (dte >= DTE_MID_THRESHOLD) return HEDGE_RATIO_DTE_MID;
        return HEDGE_RATIO_DTE_LOW;
    }

    private int findHedgeStrike(int atmStrike, String optionType, double targetPremium) {
        int direction = "CE".equals(optionType) ? 1 : -1;
        int candidate = atmStrike + (direction * STRIKE_INTERVAL);
        int bestStrike = candidate;
        double bestDiff = Double.MAX_VALUE;

        for (int i = 0; i < 10; i++) {
            String sym = marketData.resolveSymbol(UNDERLYING, candidate, optionType, TRADING_EXPIRY);
            OptionQuote q = marketData.getOptionQuote(sym);
            if (q != null && q.getLtp() > 0) {
                double diff = Math.abs(q.getLtp() - targetPremium);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestStrike = candidate;
                }
            }
            candidate += (direction * STRIKE_INTERVAL);
        }
        return bestStrike;
    }

    private StrategyLeg buildLeg(String symbol, String optionType, String side,
                                  int strike, int qty, double price) {
        return StrategyLeg.builder()
                .name(symbol).optionType(optionType).side(side)
                .strike(strike).quantity(qty)
                .entryPrice(price).currentPrice(price)
                .status("OPEN").build();
    }
}
