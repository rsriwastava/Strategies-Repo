package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.*;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * NiftyDeltaZero -- Delta-neutral ratio straddle for NIFTY.
 *
 * <h3>Overview:</h3>
 * <p>Enters a 2-leg ratio straddle (CE SELL + PE SELL) at the synthetic ATM
 * strike. The lot ratio between CE and PE is computed by a delta-balancing
 * algorithm to minimise net portfolio delta at entry. The strategy uses a
 * 4-tier lot assignment based on the absolute delta difference between CE
 * and PE:</p>
 * <pre>
 *   Tier 1: diff <= 0.05         -> 1:1 (balanced)
 *   Tier 2: 0.06 <= diff <= 0.12 -> 2:3 (mild imbalance)
 *   Tier 3: 0.13 <= diff <= 0.25 -> 1:2 (moderate)
 *   Tier 4: diff > 0.25          -> 1:2 (capped)
 * </pre>
 * <p>Higher-|delta| side always gets FEWER lots.</p>
 *
 * <h3>Entry:</h3>
 * <ul>
 *   <li>Entry cutoff: 14:30 IST</li>
 *   <li>Delta-flip cooldown and daily flip limit enforced</li>
 * </ul>
 *
 * <h3>Exit:</h3>
 * <ul>
 *   <li>PNL-based stop-loss</li>
 *   <li>Time-based (market close)</li>
 *   <li>Manual exit flag</li>
 * </ul>
 */
public class NiftyDeltaZero extends BaseStrategy {

    private static final String UNDERLYING = "NIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "currentWeek";
    private static final int MAX_RE_ENTRIES = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Delta balance tiers ────────────────────────────────────────────
    private static final double DELTA_BALANCE_BAND = 0.05;
    private static final double DELTA_MILD_UPPER = 0.12;
    private static final double DELTA_MODERATE_UPPER = 0.25;

    // ── Entry cutoff ───────────────────────────────────────────────────
    private static final int ENTRY_CUTOFF_HOUR = 14;
    private static final int ENTRY_CUTOFF_MINUTE = 30;

    // ── Delta flip controls ────────────────────────────────────────────
    private static final int MAX_DAILY_FLIPS = 3;
    private static final long FLIP_COOLDOWN_MILLIS = 5L * 60 * 1000;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Runtime state ──────────────────────────────────────────────────
    private Signal activeSignal;
    private int dailyFlipCount;
    private long lastFlipTimestamp;
    private boolean manualExitRequested;

    public NiftyDeltaZero(OrderService orderService, MarketDataProvider marketData) {
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
        // ── Entry cutoff check ─────────────────────────────────────────
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

        // ── Delta-balanced lot computation ─────────────────────────────
        double callDelta = Math.abs(ceQuote.getDelta());
        double putDelta = Math.abs(peQuote.getDelta());
        int[] lots = computeBalancedLots(callDelta, putDelta);

        StrategyLeg ceLeg = StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(atmStrike).quantity(lots[0])
                .entryPrice(ceQuote.getLtp()).currentPrice(ceQuote.getLtp())
                .status("OPEN").build();

        StrategyLeg peLeg = StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(atmStrike).quantity(lots[1])
                .entryPrice(peQuote.getLtp()).currentPrice(peQuote.getLtp())
                .status("OPEN").build();

        activeSignal = Signal.builder()
                .strategyId(0L).status("LIVE")
                .currentAtm(atmStrike).baseIndexPrice(spot)
                .addLeg(ceLeg).addLeg(peLeg)
                .build();

        orderService.placeEntryOrders(activeSignal);
        manualExitRequested = false;
        markEntry();
        logger.info("[DeltaZero] Entry atm=" + atmStrike + " ceLots=" + lots[0]
                + " peLots=" + lots[1] + " ceDelta=" + callDelta + " peDelta=" + putDelta);
    }

    @Override
    protected boolean shouldExit() {
        if (activeSignal == null) return false;
        if (manualExitRequested) return true;

        double pnl = activeSignal.getTotalUnrealisedPnl();
        if (pnl < -config.getDefaultStopLoss() && config.getDefaultStopLoss() > 0) {
            logger.info("[DeltaZero] PNL SL triggered, pnl=" + pnl);
            return true;
        }

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
        logger.info("[DeltaZero] Position exited");
    }

    @Override
    protected void onExitEvaluation() {
        // Monitor delta drift and enforce flip limits
        if (activeSignal == null) return;

        String ceSymbol = activeSignal.getLegs().get(0).getName();
        String peSymbol = activeSignal.getLegs().get(1).getName();
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) return;

        double netDelta = ceQuote.getDelta() * activeSignal.getLegs().get(0).getQuantity()
                + peQuote.getDelta() * activeSignal.getLegs().get(1).getQuantity();

        if (Math.abs(netDelta) > DELTA_MODERATE_UPPER && canFlip()) {
            logger.info("[DeltaZero] Large delta drift detected, netDelta=" + netDelta);
            dailyFlipCount++;
            lastFlipTimestamp = System.currentTimeMillis();
        }
    }

    /** Allows external manual exit request. */
    public void requestManualExit() {
        this.manualExitRequested = true;
    }

    // ── Delta balance computation ──────────────────────────────────────

    private int[] computeBalancedLots(double callDelta, double putDelta) {
        double diff = Math.abs(callDelta - putDelta);
        int callLots;
        int putLots;

        if (diff <= DELTA_BALANCE_BAND) {
            callLots = 1;
            putLots = 1;
        } else if (diff <= DELTA_MILD_UPPER) {
            if (callDelta > putDelta) { callLots = 2; putLots = 3; }
            else                      { callLots = 3; putLots = 2; }
        } else {
            if (callDelta > putDelta) { callLots = 1; putLots = 2; }
            else                      { callLots = 2; putLots = 1; }
        }
        return new int[]{callLots, putLots};
    }

    private boolean canFlip() {
        if (dailyFlipCount >= MAX_DAILY_FLIPS) return false;
        return (System.currentTimeMillis() - lastFlipTimestamp) >= FLIP_COOLDOWN_MILLIS;
    }
}
