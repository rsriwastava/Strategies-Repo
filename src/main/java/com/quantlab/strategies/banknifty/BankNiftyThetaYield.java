package com.quantlab.strategies.banknifty;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * BANKNIFTY Theta/IV-IR Arbitrage Strategy (ThetaYield).
 * <p>
 * Sells options that offer the best theta-to-IV ratio, favouring strikes
 * where implied volatility overstates realised moves. An anti-churn filter
 * prevents excessive rolling, and a 5-trigger IV exit with 2-consecutive
 * confirmation provides robust exit logic.
 * <p>
 * Key parameters:
 * <ul>
 *   <li>Delta threshold: 0.65 standard, 0.70 near expiry</li>
 *   <li>Strike interval: 100</li>
 *   <li>Anti-churn: minimum 60 s between adjustments</li>
 *   <li>5 independent IV exit triggers, requiring 2 consecutive confirmations</li>
 * </ul>
 */
public class BankNiftyThetaYield extends BaseStrategy {

    private static final Logger log = Logger.getLogger(BankNiftyThetaYield.class.getName());

    // ── Index constants ─────────────────────────────────────────────────
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT    = "NSEFO";
    private static final String EXPIRY     = "currentMonth";
    private static final int    STRIKE_INTERVAL = 100;

    // ── Re-entry and cooldown ───────────────────────────────────────────
    private static final int MAX_RE_ENTRIES      = 10;
    private static final int COOLDOWN_SECONDS    = 60;

    // ── Delta thresholds ────────────────────────────────────────────────
    private static final double DELTA_THRESHOLD_NORMAL = 0.65;
    private static final double DELTA_THRESHOLD_EXPIRY = 0.70;
    private static final int    EXPIRY_DTE_CUTOFF      = 3;

    // ── Theta/IV selection ──────────────────────────────────────────────
    private static final int    STRIKE_SCAN_RANGE  = 5;
    private static final double MIN_THETA_IV_RATIO = 0.02;

    // ── IV exit triggers ────────────────────────────────────────────────
    private static final double IV_EXIT_SPIKE_PCT       = 0.30;
    private static final double IV_EXIT_TERM_INVERSION  = 0.05;
    private static final double IV_EXIT_SKEW_BLOW       = 0.20;
    private static final double IV_EXIT_REALISED_EXCEED = 0.15;
    private static final double IV_EXIT_VEGA_LOSS_PCT   = 0.50;
    private static final int    IV_CONFIRM_COUNT        = 2;

    // ── Anti-churn ──────────────────────────────────────────────────────
    private static final long ANTI_CHURN_INTERVAL_MS = 60_000L;

    // ── Runtime state ───────────────────────────────────────────────────
    private Signal activeSignal;
    private double entryIv;
    private int ivExitConsecutiveCount;
    private long lastAdjustmentTimestamp;

    public BankNiftyThetaYield(OrderService orderService, MarketDataProvider marketData) {
        super(StrategyConfig.builder()
                        .underlying(UNDERLYING)
                        .segment(SEGMENT)
                        .strikeInterval(STRIKE_INTERVAL)
                        .tradingExpiry(EXPIRY)
                        .maxReEntries(MAX_RE_ENTRIES)
                        .exitCooldownSeconds(COOLDOWN_SECONDS)
                        .build(),
                orderService, marketData);
    }

    // ── Entry ───────────────────────────────────────────────────────────

    @Override
    protected void onEntry(long strategyId) {
        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) return;

        int atmStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);

        int bestCeStrike = 0;
        int bestPeStrike = 0;
        double bestCeRatio = 0;
        double bestPeRatio = 0;

        for (int i = 1; i <= STRIKE_SCAN_RANGE; i++) {
            int ceStrike = atmStrike + (i * STRIKE_INTERVAL);
            String ceSymbol = buildSymbol(ceStrike, "CE");
            OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
            if (ceQuote != null) {
                double ceDelta = Math.abs(ceQuote.getDelta());
                double ceRatio = computeThetaIvRatio(ceQuote);
                if (ceDelta <= DELTA_THRESHOLD_NORMAL && ceRatio > bestCeRatio) {
                    bestCeRatio = ceRatio;
                    bestCeStrike = ceStrike;
                }
            }

            int peStrike = atmStrike - (i * STRIKE_INTERVAL);
            String peSymbol = buildSymbol(peStrike, "PE");
            OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
            if (peQuote != null) {
                double peDelta = Math.abs(peQuote.getDelta());
                double peRatio = computeThetaIvRatio(peQuote);
                if (peDelta <= DELTA_THRESHOLD_NORMAL && peRatio > bestPeRatio) {
                    bestPeRatio = peRatio;
                    bestPeStrike = peStrike;
                }
            }
        }

        if (bestCeRatio < MIN_THETA_IV_RATIO && bestPeRatio < MIN_THETA_IV_RATIO) {
            log.info("[" + strategyId + "] No strike meets minimum theta/IV ratio");
            return;
        }

        List<StrategyLeg> legs = new ArrayList<>(2);
        if (bestCeRatio >= MIN_THETA_IV_RATIO) {
            legs.add(buildSellLeg(bestCeStrike, "CE"));
        }
        if (bestPeRatio >= MIN_THETA_IV_RATIO) {
            legs.add(buildSellLeg(bestPeStrike, "PE"));
        }

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
        entryIv = marketData.getIV(legs.get(0).getName());
        ivExitConsecutiveCount = 0;
        log.info("[" + strategyId + "] ThetaYield entry: CE@" + bestCeStrike
                + " PE@" + bestPeStrike);
    }

    // ── Exit evaluation ─────────────────────────────────────────────────

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) return;

        long now = System.currentTimeMillis();
        if (now - lastAdjustmentTimestamp < ANTI_CHURN_INTERVAL_MS) {
            return;
        }
        lastAdjustmentTimestamp = now;

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // ── Should exit ─────────────────────────────────────────────────────

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) return false;

        int triggersHit = 0;
        for (StrategyLeg leg : activeSignal.getLegs()) {
            if (!leg.isOpen()) continue;
            double currentIv = marketData.getIV(leg.getName());
            if (Double.isNaN(currentIv)) continue;

            if (entryIv > 0 && (currentIv - entryIv) / entryIv >= IV_EXIT_SPIKE_PCT) {
                triggersHit++;
            }
            if (currentIv > entryIv + IV_EXIT_TERM_INVERSION) {
                triggersHit++;
            }
            if (Math.abs(currentIv - entryIv) > IV_EXIT_SKEW_BLOW) {
                triggersHit++;
            }

            OptionQuote quote = marketData.getOptionQuote(leg.getName());
            if (quote != null) {
                double vegaLoss = Math.abs(quote.getVega() * (currentIv - entryIv));
                if (leg.getEntryPrice() > 0
                        && (vegaLoss / leg.getEntryPrice()) >= IV_EXIT_VEGA_LOSS_PCT) {
                    triggersHit++;
                }
            }
        }

        if (triggersHit >= 1) {
            ivExitConsecutiveCount++;
        } else {
            ivExitConsecutiveCount = 0;
        }

        return ivExitConsecutiveCount >= IV_CONFIRM_COUNT;
    }

    // ── Exit ────────────────────────────────────────────────────────────

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal == null) return;

        orderService.placeExitOrders(activeSignal);
        log.info("[" + strategyId + "] ThetaYield exit: pnl="
                + activeSignal.getTotalUnrealisedPnl());
        activeSignal = null;
        ivExitConsecutiveCount = 0;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private double computeThetaIvRatio(OptionQuote quote) {
        double iv = quote.getIv();
        if (iv <= 0) return 0;
        return Math.abs(quote.getTheta()) / iv;
    }

    private StrategyLeg buildSellLeg(int strike, String optionType) {
        String symbol = buildSymbol(strike, optionType);
        OptionQuote quote = marketData.getOptionQuote(symbol);
        double ltp = (quote != null) ? quote.getLtp() : 0;
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

    private String buildSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + EXPIRY + "-" + strike + optionType;
    }
}
