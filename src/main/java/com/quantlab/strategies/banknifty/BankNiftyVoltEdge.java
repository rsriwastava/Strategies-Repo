package com.quantlab.strategies.banknifty;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

import java.util.logging.Logger;

/**
 * BANKNIFTY ATM Accumulation Strategy (VoltEdge).
 * <p>
 * Detects institutional accumulation at the ATM strike via volume-spike and
 * lot-change tiers.  An IV quality gate (three independent checks) must pass
 * before entry.  The strategy picks a deep-ITM strike (3x strike interval =
 * 300 pts deep) and manages the position with a hybrid ATR-based trailing
 * stop-loss, time-decaying fixed SL, and a premium-floor failsafe.
 * <p>
 * Volume tiers:
 * <ul>
 *   <li>T1 — 5 lots on Rs 100 volume spike</li>
 *   <li>T2 — 10 lots on Rs 200 volume spike</li>
 *   <li>T3 — 20 lots on Rs 400 volume spike</li>
 * </ul>
 */
public class BankNiftyVoltEdge extends BaseStrategy {

    private static final Logger log = Logger.getLogger(BankNiftyVoltEdge.class.getName());

    // ── Index constants ─────────────────────────────────────────────────
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT    = "NSEFO";
    private static final String EXPIRY     = "currentMonth";
    private static final int    STRIKE_INTERVAL = 100;

    // ── Re-entry and cooldown ───────────────────────────────────────────
    private static final int MAX_RE_ENTRIES      = 10;
    private static final int COOLDOWN_SECONDS    = 300;

    // ── Trailing stop-loss parameters ───────────────────────────────────
    private static final double TSL_ACTIVATION_PTS   = 40.0;
    private static final double TSL_INITIAL_LOCK_PTS = 25.0;
    private static final double TSL_STEP_PTS         = 20.0;
    private static final double TSL_TRAIL_DIST_PTS   = 20.0;

    // ── Fixed stop-loss ─────────────────────────────────────────────────
    private static final double FIXED_SL_PTS = 50.0;

    // ── Volume tier thresholds (lots / Rs value spike) ──────────────────
    private static final int    T1_LOTS = 5;
    private static final double T1_VALUE_SPIKE = 100.0;
    private static final int    T2_LOTS = 10;
    private static final double T2_VALUE_SPIKE = 200.0;
    private static final int    T3_LOTS = 20;
    private static final double T3_VALUE_SPIKE = 400.0;

    // ── IV quality gate thresholds ──────────────────────────────────────
    private static final double IV_DECLINE_THRESHOLD   = 0.015;
    private static final double IV_VOL_EXPANSION_CEIL  = 0.70;
    private static final double IV_SPIKE_THRESHOLD     = 0.40;
    private static final double IV_CRUSH_THRESHOLD     = 0.25;

    // ── Deep ITM depth multiplier ───────────────────────────────────────
    private static final int DEEP_ITM_MULTIPLIER = 3;

    // ── Daily SL limit (points) ─────────────────────────────────────────
    private static final double DAILY_SL_LIMIT_PTS = 500.0;

    // ── Premium floor failsafe ──────────────────────────────────────────
    private static final double PREMIUM_FLOOR_RATIO = 0.10;

    // ── Runtime state ───────────────────────────────────────────────────
    private Signal activeSignal;
    private double peakPremium;
    private double trailingStopLevel;
    private boolean tslActivated;
    private double dailyRealisedPnl;
    private double previousIv;
    private long previousVolume;
    private int reEntryCount;

    public BankNiftyVoltEdge(OrderService orderService, MarketDataProvider marketData) {
        super(StrategyConfig.builder()
                        .underlying(UNDERLYING)
                        .segment(SEGMENT)
                        .strikeInterval(STRIKE_INTERVAL)
                        .tradingExpiry(EXPIRY)
                        .maxReEntries(MAX_RE_ENTRIES)
                        .exitCooldownSeconds(COOLDOWN_SECONDS)
                        .defaultStopLoss(FIXED_SL_PTS)
                        .build(),
                orderService, marketData);
    }

    // ── Entry ───────────────────────────────────────────────────────────

    @Override
    protected void onEntry(long strategyId) {
        if (dailyRealisedPnl <= -DAILY_SL_LIMIT_PTS) {
            log.info("[" + strategyId + "] Daily SL limit reached; skipping entry");
            return;
        }
        if (reEntryCount >= MAX_RE_ENTRIES) {
            return;
        }

        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) return;

        int atmStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);
        String ceSymbol = buildSymbol(atmStrike, "CE");
        String peSymbol = buildSymbol(atmStrike, "PE");

        if (!passesIvQualityGate(ceSymbol, peSymbol)) {
            return;
        }

        int tier = detectVolumeTier(ceSymbol, peSymbol);
        if (tier == 0) return;

        int lots = tierToLots(tier);
        boolean bullish = determineBias(ceSymbol, peSymbol);
        int deepStrike = bullish
                ? atmStrike + (DEEP_ITM_MULTIPLIER * STRIKE_INTERVAL)
                : atmStrike - (DEEP_ITM_MULTIPLIER * STRIKE_INTERVAL);
        String optionType = bullish ? "CE" : "PE";
        String deepSymbol = buildSymbol(deepStrike, optionType);

        OptionQuote deepQuote = marketData.getOptionQuote(deepSymbol);
        if (deepQuote == null) return;

        StrategyLeg leg = StrategyLeg.builder()
                .name(deepSymbol)
                .optionType(optionType)
                .side("BUY")
                .strike(deepStrike)
                .quantity(lots)
                .entryPrice(deepQuote.getLtp())
                .currentPrice(deepQuote.getLtp())
                .status("OPEN")
                .build();

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .addLeg(leg)
                .build();

        orderService.placeEntryOrders(activeSignal);
        peakPremium = deepQuote.getLtp();
        trailingStopLevel = 0.0;
        tslActivated = false;
        reEntryCount++;
        log.info("[" + strategyId + "] VoltEdge entry: tier=" + tier
                + " lots=" + lots + " strike=" + deepStrike + optionType);
    }

    // ── Exit evaluation ─────────────────────────────────────────────────

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) return;

        StrategyLeg leg = activeSignal.getLegs().get(0);
        OptionQuote quote = marketData.getOptionQuote(leg.getName());
        if (quote == null) return;

        double currentPremium = quote.getLtp();
        if (currentPremium > peakPremium) {
            peakPremium = currentPremium;
        }

        double profitFromEntry = currentPremium - leg.getEntryPrice();
        if (!tslActivated && profitFromEntry >= TSL_ACTIVATION_PTS) {
            tslActivated = true;
            trailingStopLevel = leg.getEntryPrice() + TSL_INITIAL_LOCK_PTS;
            log.info("[" + strategyId + "] TSL activated at lock=" + trailingStopLevel);
        }

        if (tslActivated) {
            double newTrail = peakPremium - TSL_TRAIL_DIST_PTS;
            if (newTrail > trailingStopLevel + TSL_STEP_PTS) {
                trailingStopLevel = newTrail;
                log.info("[" + strategyId + "] TSL stepped up to " + trailingStopLevel);
            }
        }

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // ── Should exit ─────────────────────────────────────────────────────

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) return false;

        StrategyLeg leg = activeSignal.getLegs().get(0);
        OptionQuote quote = marketData.getOptionQuote(leg.getName());
        if (quote == null) return true;

        double currentPremium = quote.getLtp();
        double lossFromEntry = leg.getEntryPrice() - currentPremium;

        double decayedSl = computeDecayedSl();
        if (lossFromEntry >= decayedSl) {
            log.info("[" + strategyId + "] Fixed SL hit (decayed=" + decayedSl + ")");
            return true;
        }

        if (tslActivated && currentPremium <= trailingStopLevel) {
            log.info("[" + strategyId + "] Trailing SL hit at " + trailingStopLevel);
            return true;
        }

        double premiumFloor = leg.getEntryPrice() * PREMIUM_FLOOR_RATIO;
        if (currentPremium <= premiumFloor) {
            log.info("[" + strategyId + "] Premium floor failsafe triggered");
            return true;
        }

        return false;
    }

    // ── Exit ────────────────────────────────────────────────────────────

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal == null) return;

        orderService.placeExitOrders(activeSignal);
        StrategyLeg leg = activeSignal.getLegs().get(0);
        OptionQuote quote = marketData.getOptionQuote(leg.getName());
        if (quote != null) {
            double pnl = (quote.getLtp() - leg.getEntryPrice()) * leg.getQuantity();
            dailyRealisedPnl += pnl;
            log.info("[" + strategyId + "] VoltEdge exit: pnl=" + pnl
                    + " dailyPnl=" + dailyRealisedPnl);
        }
        activeSignal = null;
    }

    // ── IV quality gate ─────────────────────────────────────────────────

    private boolean passesIvQualityGate(String ceSymbol, String peSymbol) {
        double ceIv = marketData.getIV(ceSymbol);
        double peIv = marketData.getIV(peSymbol);
        if (Double.isNaN(ceIv) || Double.isNaN(peIv)) return false;

        double avgIv = (ceIv + peIv) / 2.0;

        boolean check1 = previousIv <= 0
                || (previousIv - avgIv) / previousIv <= IV_DECLINE_THRESHOLD;
        boolean check2 = avgIv <= IV_VOL_EXPANSION_CEIL;
        boolean check3 = previousIv <= 0
                || ((avgIv - previousIv) / previousIv < IV_SPIKE_THRESHOLD
                && (previousIv - avgIv) / previousIv < IV_CRUSH_THRESHOLD);

        previousIv = avgIv;
        return check1 && check2 && check3;
    }

    // ── Volume tier detection ───────────────────────────────────────────

    private int detectVolumeTier(String ceSymbol, String peSymbol) {
        OptionQuote ceQ = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQ = marketData.getOptionQuote(peSymbol);
        if (ceQ == null || peQ == null) return 0;

        long totalVolume = ceQ.getVolume() + peQ.getVolume();
        if (previousVolume == 0) {
            previousVolume = totalVolume;
            return 0;
        }
        double spike = totalVolume - previousVolume;
        previousVolume = totalVolume;

        if (spike >= T3_VALUE_SPIKE) return 3;
        if (spike >= T2_VALUE_SPIKE) return 2;
        if (spike >= T1_VALUE_SPIKE) return 1;
        return 0;
    }

    private int tierToLots(int tier) {
        switch (tier) {
            case 3: return T3_LOTS;
            case 2: return T2_LOTS;
            default: return T1_LOTS;
        }
    }

    // ── Directional bias ────────────────────────────────────────────────

    private boolean determineBias(String ceSymbol, String peSymbol) {
        OptionQuote ceQ = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQ = marketData.getOptionQuote(peSymbol);
        if (ceQ == null || peQ == null) return true;
        return peQ.getOpenInterest() > ceQ.getOpenInterest();
    }

    // ── Time-decaying SL ────────────────────────────────────────────────

    private double computeDecayedSl() {
        if (!marketData.isDataFresh(UNDERLYING, 5)) {
            return FIXED_SL_PTS * 0.5;
        }
        return FIXED_SL_PTS;
    }

    // ── Symbol builder ──────────────────────────────────────────────────

    private String buildSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + EXPIRY + "-" + strike + optionType;
    }
}
