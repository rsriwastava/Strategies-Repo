package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.*;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * NiftyThetaYield -- IV-calibrated ATM straddle selling (ThetaEdge) for NIFTY.
 *
 * <h3>Overview:</h3>
 * <p>Compares Spot ATM vs Future ATM straddle premiums and selects the strike
 * with the higher theta/IV ratio (better compensation per unit of vol risk).
 * Entry filter enforces |delta| <= 0.65 (0.70 on expiry day). Anti-churn rule
 * blocks re-entry at the same strike as the last exit.</p>
 *
 * <h3>Exit Framework (5 triggers, first to fire wins):</h3>
 * <ol>
 *   <li>IV Spike L1: current max IV > entry max IV by 10-12%</li>
 *   <li>IV Spike L2: current avg IV > entry avg IV by 12-15%</li>
 *   <li>IV Crush L1: current min IV &lt; entry min IV by 15-18%</li>
 *   <li>IV Crush L2: current avg IV &lt; entry avg IV by 18-20%</li>
 *   <li>Delta Fence: either leg |delta| > 0.75</li>
 * </ol>
 * <p>Each trigger requires 2 consecutive tick confirmations.</p>
 *
 * <h3>Legs:</h3>
 * <pre>
 *   Leg 1: ATM CE SELL
 *   Leg 2: ATM PE SELL
 * </pre>
 */
public class NiftyThetaYield extends BaseStrategy {

    private static final String UNDERLYING = "NIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int MAX_RE_ENTRIES = 10;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Delta entry filters ────────────────────────────────────────────
    private static final double DELTA_ENTRY_NON_EXPIRY = 0.65;
    private static final double DELTA_ENTRY_EXPIRY = 0.70;

    // ── IV exit thresholds (percentage change from entry baseline) ─────
    private static final double IV_SPIKE_L1_PCT = 10.0;
    private static final double IV_SPIKE_L2_PCT = 12.0;
    private static final double IV_CRUSH_L1_PCT = 15.0;
    private static final double IV_CRUSH_L2_PCT = 18.0;
    private static final double DELTA_FENCE = 0.75;
    private static final int CONFIRMATION_TICKS_REQUIRED = 2;

    // ── Expiry-day cutoff ──────────────────────────────────────────────
    private static final int EXPIRY_CUTOFF_HOUR = 14;
    private static final int EXPIRY_CUTOFF_MINUTE = 30;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Runtime state ──────────────────────────────────────────────────
    private Signal activeSignal;
    private int selectedStrike;
    private double entryMaxIv;
    private double entryMinIv;
    private double entryAvgIv;
    private int lastExitedStrike = -1;
    private String lastTriggeredExit;
    private int confirmationCount;

    public NiftyThetaYield(OrderService orderService, MarketDataProvider marketData) {
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

        // Fetch future price and compute future ATM
        String futSymbol = marketData.resolveSymbol(UNDERLYING, 0, "CE", TRADING_EXPIRY);
        double futurePrice = marketData.getATR(UNDERLYING, 0); // placeholder for future price
        int futureAtm = roundToStrike(futurePrice > 0 ? futurePrice : spot);

        // Compute theta/IV ratio for each candidate
        double spotRatio = computeThetaIvRatio(spotAtm);
        double futRatio = computeThetaIvRatio(futureAtm);

        boolean useSpot = spotRatio >= futRatio;
        int candidate = useSpot ? spotAtm : futureAtm;

        // Anti-churn: block same strike as last exit
        if (candidate == lastExitedStrike) {
            logger.info("[ThetaYield] Anti-churn blocked strike=" + candidate);
            return;
        }

        // Delta entry filter
        String ceSymbol = marketData.resolveSymbol(UNDERLYING, candidate, "CE", TRADING_EXPIRY);
        String peSymbol = marketData.resolveSymbol(UNDERLYING, candidate, "PE", TRADING_EXPIRY);
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) return;

        boolean isExpiry = marketData.getDTE(UNDERLYING, TRADING_EXPIRY) == 0;
        double deltaThreshold = isExpiry ? DELTA_ENTRY_EXPIRY : DELTA_ENTRY_NON_EXPIRY;
        double maxDelta = Math.max(Math.abs(ceQuote.getDelta()), Math.abs(peQuote.getDelta()));
        if (maxDelta > deltaThreshold) {
            logger.info("[ThetaYield] Delta filter blocked, maxDelta=" + maxDelta);
            return;
        }

        // Expiry day 14:30 cutoff
        if (isExpiry) {
            LocalTime now = LocalTime.now(IST);
            if (now.isAfter(LocalTime.of(EXPIRY_CUTOFF_HOUR, EXPIRY_CUTOFF_MINUTE))) {
                return;
            }
        }

        // Record IV baselines
        entryMaxIv = Math.max(ceQuote.getIv(), peQuote.getIv());
        entryMinIv = Math.min(ceQuote.getIv(), peQuote.getIv());
        entryAvgIv = (ceQuote.getIv() + peQuote.getIv()) / 2.0;
        selectedStrike = candidate;

        // Build 2-leg signal: CE SELL + PE SELL
        StrategyLeg ceLeg = StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(candidate).quantity(1)
                .entryPrice(ceQuote.getLtp()).currentPrice(ceQuote.getLtp())
                .status("OPEN").build();
        StrategyLeg peLeg = StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(candidate).quantity(1)
                .entryPrice(peQuote.getLtp()).currentPrice(peQuote.getLtp())
                .status("OPEN").build();

        activeSignal = Signal.builder()
                .strategyId(0L).status("LIVE")
                .currentAtm(candidate).baseIndexPrice(spot)
                .addLeg(ceLeg).addLeg(peLeg)
                .build();

        orderService.placeEntryOrders(activeSignal);
        confirmationCount = 0;
        lastTriggeredExit = null;
        markEntry();
        logger.info("[ThetaYield] Entered strike=" + candidate + " ceIV=" + ceQuote.getIv()
                + " peIV=" + peQuote.getIv());
    }

    @Override
    protected boolean shouldExit() {
        if (activeSignal == null) return false;

        String ceSymbol = marketData.resolveSymbol(UNDERLYING, selectedStrike, "CE", TRADING_EXPIRY);
        String peSymbol = marketData.resolveSymbol(UNDERLYING, selectedStrike, "PE", TRADING_EXPIRY);
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) return true;

        double curMaxIv = Math.max(ceQuote.getIv(), peQuote.getIv());
        double curMinIv = Math.min(ceQuote.getIv(), peQuote.getIv());
        double curAvgIv = (ceQuote.getIv() + peQuote.getIv()) / 2.0;

        if (entryMaxIv == 0 || entryMinIv == 0 || entryAvgIv == 0) return false;

        // ── Evaluate 5 exit triggers ───────────────────────────────────
        String trigger = null;
        double maxIvPct = ((curMaxIv - entryMaxIv) / entryMaxIv) * 100.0;
        double avgIvPct = ((curAvgIv - entryAvgIv) / entryAvgIv) * 100.0;
        double minIvPct = ((curMinIv - entryMinIv) / entryMinIv) * 100.0;

        if (maxIvPct > IV_SPIKE_L1_PCT)        trigger = "IV_SPIKE_L1";
        else if (avgIvPct > IV_SPIKE_L2_PCT)   trigger = "IV_SPIKE_L2";
        else if (minIvPct < -IV_CRUSH_L1_PCT)  trigger = "IV_CRUSH_L1";
        else if (avgIvPct < -IV_CRUSH_L2_PCT)  trigger = "IV_CRUSH_L2";
        else if (Math.abs(ceQuote.getDelta()) > DELTA_FENCE
                || Math.abs(peQuote.getDelta()) > DELTA_FENCE) {
            trigger = "DELTA_FENCE";
        }

        // Two-consecutive-confirmation logic
        if (trigger != null) {
            if (trigger.equals(lastTriggeredExit)) {
                confirmationCount++;
                if (confirmationCount >= CONFIRMATION_TICKS_REQUIRED) {
                    logger.info("[ThetaYield] Exit confirmed: " + trigger);
                    return true;
                }
            } else {
                lastTriggeredExit = trigger;
                confirmationCount = 1;
            }
        } else {
            lastTriggeredExit = null;
            confirmationCount = 0;
        }
        return false;
    }

    @Override
    protected void onExit() {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            lastExitedStrike = selectedStrike;
            activeSignal = null;
        }
        markExit();
        logger.info("[ThetaYield] Exited strike=" + selectedStrike);
    }

    @Override
    protected void onExitEvaluation() {
        // IV monitoring happens in shouldExit()
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private double computeThetaIvRatio(int strike) {
        String ceS = marketData.resolveSymbol(UNDERLYING, strike, "CE", TRADING_EXPIRY);
        String peS = marketData.resolveSymbol(UNDERLYING, strike, "PE", TRADING_EXPIRY);
        OptionQuote ce = marketData.getOptionQuote(ceS);
        OptionQuote pe = marketData.getOptionQuote(peS);
        if (ce == null || pe == null) return 0.0;
        double avgIv = (ce.getIv() + pe.getIv()) / 2.0;
        if (avgIv == 0.0) return 0.0;
        double combinedTheta = Math.abs(ce.getTheta()) + Math.abs(pe.getTheta());
        return combinedTheta / avgIv;
    }
}
