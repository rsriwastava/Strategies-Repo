package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.*;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * NiftyContraHedge -- Inverted-hedge delta-neutral straddle for NIFTY.
 *
 * <h3>Overview:</h3>
 * <p>Identical to ShieldedNeutral in structure (4-leg hedged straddle) but with
 * an INVERTED hedge assignment. Instead of CE hedge matching CE SELL lots and
 * PE hedge matching PE SELL lots, the assignments are crossed:</p>
 * <ul>
 *   <li>CE BUY hedge receives PE SELL lot count (not CE SELL lots)</li>
 *   <li>PE BUY hedge receives CE SELL lot count (not PE SELL lots)</li>
 * </ul>
 * <p>This creates a contrarian protection bias: the hedge on each side is
 * sized based on the exposure of the opposite leg, providing asymmetric
 * protection that benefits from mean-reversion scenarios.</p>
 *
 * <h3>Hedge Strike Selection (DTE-scaled, same as ShieldedNeutral):</h3>
 * <pre>
 *   DTE >= 4 -> 10% of short premium
 *   DTE 2-3  -> 15% of short premium
 *   DTE 0-1  -> 22% of short premium
 * </pre>
 *
 * <h3>Legs:</h3>
 * <pre>
 *   Leg 1: ATM CE SELL (delta-balanced lots)
 *   Leg 2: ATM PE SELL (delta-balanced lots)
 *   Leg 3: OTM CE BUY (PE SELL lots -- INVERTED)
 *   Leg 4: OTM PE BUY (CE SELL lots -- INVERTED)
 * </pre>
 */
public class NiftyContraHedge extends BaseStrategy {

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

    private static final int ENTRY_CUTOFF_HOUR = 14;
    private static final int ENTRY_CUTOFF_MINUTE = 30;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Runtime state ──────────────────────────────────────────────────
    private Signal activeSignal;
    private boolean manualExitRequested;

    public NiftyContraHedge(OrderService orderService, MarketDataProvider marketData) {
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

        // Delta-balanced lots
        double callDelta = Math.abs(ceQuote.getDelta());
        double putDelta = Math.abs(peQuote.getDelta());
        int[] lots = computeBalancedLots(callDelta, putDelta);
        int ceLots = lots[0];
        int peLots = lots[1];

        // Hedge strike selection (DTE-scaled)
        int dte = marketData.getDTE(UNDERLYING, TRADING_EXPIRY);
        double hedgeRatio = getHedgePremiumRatio(dte);
        int ceHedgeStrike = findHedgeStrike(atmStrike, "CE", ceQuote.getLtp() * hedgeRatio);
        int peHedgeStrike = findHedgeStrike(atmStrike, "PE", peQuote.getLtp() * hedgeRatio);

        String ceHedgeSym = marketData.resolveSymbol(UNDERLYING, ceHedgeStrike, "CE", TRADING_EXPIRY);
        String peHedgeSym = marketData.resolveSymbol(UNDERLYING, peHedgeStrike, "PE", TRADING_EXPIRY);
        OptionQuote ceHedgeQ = marketData.getOptionQuote(ceHedgeSym);
        OptionQuote peHedgeQ = marketData.getOptionQuote(peHedgeSym);
        if (ceHedgeQ == null || peHedgeQ == null) return;

        // INVERTED hedge assignment: CE hedge gets PE lots, PE hedge gets CE lots
        StrategyLeg ceSell = buildLeg(ceSymbol, "CE", "SELL", atmStrike, ceLots, ceQuote.getLtp());
        StrategyLeg peSell = buildLeg(peSymbol, "PE", "SELL", atmStrike, peLots, peQuote.getLtp());
        StrategyLeg ceBuy = buildLeg(ceHedgeSym, "CE", "BUY", ceHedgeStrike, peLots, ceHedgeQ.getLtp());
        StrategyLeg peBuy = buildLeg(peHedgeSym, "PE", "BUY", peHedgeStrike, ceLots, peHedgeQ.getLtp());

        activeSignal = Signal.builder()
                .strategyId(0L).status("LIVE")
                .currentAtm(atmStrike).baseIndexPrice(spot)
                .addLeg(ceSell).addLeg(peSell)
                .addLeg(ceBuy).addLeg(peBuy)
                .build();

        orderService.placeEntryOrders(activeSignal);
        manualExitRequested = false;
        markEntry();
        logger.info("[ContraHedge] Entry atm=" + atmStrike
                + " ceLots=" + ceLots + " peLots=" + peLots
                + " ceHedgeLots=" + peLots + "(inverted) peHedgeLots=" + ceLots + "(inverted)");
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
        logger.info("[ContraHedge] Position exited");
    }

    @Override
    protected void onExitEvaluation() {
        // Delta drift monitoring
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
