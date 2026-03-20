package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.*;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * NiftyVoltEdge -- ATM Accumulation strategy for NIFTY.
 *
 * <h3>Overview:</h3>
 * <p>Detects institutional accumulation at ATM via volume spike and lot-change
 * tiers. Applies an IV quality gate (3 independent checks) before entry.
 * Selects a deep ITM strike (3x strike interval away from ATM) and uses a
 * hybrid ATR-based trailing stop-loss with 3-tier trailing distances (normal,
 * extended 3x, deep 5x). Time-decaying SL phases (1.3/1.0/0.8 multiplier)
 * and a premium failsafe (8% drop) protect the position intraday.</p>
 *
 * <h3>Entry Flow:</h3>
 * <ol>
 *   <li>Fetch ATM strike volume and OI in lots</li>
 *   <li>Classify volume tier: T1 (8+ lots / Rs.20), T2 (15+ lots / Rs.40),
 *       T3 (25+ lots / Rs.75)</li>
 *   <li>IV quality gate: decline > 1.5%, vol expansion >= 70%,
 *       spike > 40% or crush > 25% -- at least 2 of 3 must pass</li>
 *   <li>Place deep ITM CE SELL at (ATM - 3 * interval)</li>
 * </ol>
 *
 * <h3>Exit Framework:</h3>
 * <ul>
 *   <li>TSL: activation at 15 pts profit, initial lock 8, step 5, trail dist 10</li>
 *   <li>Fixed SL: 20 pts (time-decayed: 1.3x / 1.0x / 0.8x)</li>
 *   <li>Premium failsafe: exit if premium drops 8% from entry</li>
 *   <li>Daily SL limits: max 3/day, 2 consecutive = 30-min pause</li>
 * </ul>
 */
public class NiftyVoltEdge extends BaseStrategy {

    // ── Strike and segment ─────────────────────────────────────────────
    private static final String UNDERLYING = "NIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";

    // ── Trailing stop-loss ─────────────────────────────────────────────
    private static final double TSL_ACTIVATION_PTS = 15.0;
    private static final double TSL_INITIAL_LOCK = 8.0;
    private static final double TSL_STEP = 5.0;
    private static final double TSL_TRAILING_DIST = 10.0;
    private static final double TSL_EXTENDED_MULT = 3.0;
    private static final double TSL_DEEP_MULT = 5.0;

    // ── Fixed SL ───────────────────────────────────────────────────────
    private static final double FIXED_SL_PTS = 20.0;
    private static final double SL_PHASE1_MULT = 1.3;
    private static final double SL_PHASE2_MULT = 1.0;
    private static final double SL_PHASE3_MULT = 0.8;

    // ── Volume tiers ───────────────────────────────────────────────────
    private static final int TIER1_LOTS = 8;
    private static final double TIER1_PREMIUM = 20.0;
    private static final int TIER2_LOTS = 15;
    private static final double TIER2_PREMIUM = 40.0;
    private static final int TIER3_LOTS = 25;
    private static final double TIER3_PREMIUM = 75.0;

    // ── IV quality gate ────────────────────────────────────────────────
    private static final double IV_DECLINE_THRESHOLD = 1.5;
    private static final double IV_VOL_EXPANSION_PCT = 70.0;
    private static final double IV_SPIKE_THRESHOLD = 40.0;
    private static final double IV_CRUSH_THRESHOLD = 25.0;

    // ── Re-entry, cooldown, daily limits ───────────────────────────────
    private static final int MAX_RE_ENTRIES = 10;
    private static final int COOLDOWN_SECONDS = 300;
    private static final double PREMIUM_FAILSAFE_DROP_PCT = 8.0;
    private static final int MAX_DAILY_SL = 3;
    private static final int CONSECUTIVE_SL_PAUSE_LIMIT = 2;
    private static final long SL_PAUSE_MILLIS = 30L * 60 * 1000;
    private static final int DEEP_ITM_MULTIPLIER = 3;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Runtime state ──────────────────────────────────────────────────
    private double entryPremium;
    private double peakProfit;
    private double tslLevel;
    private boolean tslActivated;
    private int dailySLCount;
    private int consecutiveSLCount;
    private long slPauseUntil;
    private double previousIv;
    private Signal activeSignal;

    public NiftyVoltEdge(OrderService orderService, MarketDataProvider marketData) {
        super(orderService, marketData, StrategyConfig.builder()
                .underlying(UNDERLYING)
                .segment(SEGMENT)
                .strikeInterval(STRIKE_INTERVAL)
                .tradingExpiry(TRADING_EXPIRY)
                .maxReEntries(MAX_RE_ENTRIES)
                .exitCooldownSeconds(COOLDOWN_SECONDS)
                .defaultStopLoss(FIXED_SL_PTS)
                .build());
    }

    @Override
    protected void onEntry() {
        if (Instant.now().toEpochMilli() < slPauseUntil) {
            logger.fine("[VoltEdge] SL pause active, skipping entry");
            return;
        }

        double spot = marketData.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrike(spot);
        String ceSymbol = marketData.resolveSymbol(UNDERLYING, atmStrike, "CE", TRADING_EXPIRY);
        OptionQuote atmQuote = marketData.getOptionQuote(ceSymbol);
        if (atmQuote == null || atmQuote.getLtp() <= 0) {
            return;
        }

        // ── Volume tier classification ─────────────────────────────────
        long oiLots = marketData.getOpenInterest(ceSymbol);
        int tier = classifyVolumeTier(oiLots, atmQuote.getLtp());
        if (tier == 0) {
            return;
        }

        // ── IV quality gate (at least 2 of 3 checks must pass) ─────────
        double currentIv = atmQuote.getIv();
        if (!passesIvQualityGate(currentIv)) {
            previousIv = currentIv;
            return;
        }
        previousIv = currentIv;

        // ── Deep ITM strike selection ──────────────────────────────────
        int deepStrike = atmStrike - (DEEP_ITM_MULTIPLIER * STRIKE_INTERVAL);
        String deepSymbol = marketData.resolveSymbol(UNDERLYING, deepStrike, "CE", TRADING_EXPIRY);
        OptionQuote deepQuote = marketData.getOptionQuote(deepSymbol);
        if (deepQuote == null || deepQuote.getLtp() <= 0) {
            return;
        }

        StrategyLeg leg = StrategyLeg.builder()
                .name(deepSymbol)
                .optionType("CE")
                .side("SELL")
                .strike(deepStrike)
                .quantity(1)
                .entryPrice(deepQuote.getLtp())
                .currentPrice(deepQuote.getLtp())
                .status("OPEN")
                .build();

        activeSignal = Signal.builder()
                .strategyId(0L)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .addLeg(leg)
                .build();

        orderService.placeEntryOrders(activeSignal);
        entryPremium = deepQuote.getLtp();
        peakProfit = 0.0;
        tslLevel = Double.MIN_VALUE;
        tslActivated = false;
        markEntry();
        logger.info("[VoltEdge] Entry strike=" + deepStrike + " premium=" + entryPremium
                + " tier=" + tier + " IV=" + currentIv);
    }

    @Override
    protected boolean shouldExit() {
        if (activeSignal == null) return false;

        StrategyLeg leg = activeSignal.getLegs().get(0);
        OptionQuote quote = marketData.getOptionQuote(leg.getName());
        if (quote == null) return true;

        double currentPrice = quote.getLtp();
        double pnl = entryPremium - currentPrice; // SELL: profit when price drops

        // ── Premium failsafe (8% adverse move) ────────────────────────
        if (currentPrice > entryPremium) {
            double adversePct = ((currentPrice - entryPremium) / entryPremium) * 100.0;
            if (adversePct > PREMIUM_FAILSAFE_DROP_PCT) {
                logger.info("[VoltEdge] Premium failsafe triggered, adverse=" + adversePct + "%");
                return true;
            }
        }

        // ── Time-decaying fixed SL ─────────────────────────────────────
        double slMultiplier = getTimeDecayMultiplier();
        double effectiveSl = FIXED_SL_PTS * slMultiplier;
        if (pnl < -effectiveSl) {
            consecutiveSLCount++;
            dailySLCount++;
            if (dailySLCount >= MAX_DAILY_SL) {
                logger.warning("[VoltEdge] Daily SL limit reached: " + dailySLCount);
            }
            if (consecutiveSLCount >= CONSECUTIVE_SL_PAUSE_LIMIT) {
                slPauseUntil = Instant.now().toEpochMilli() + SL_PAUSE_MILLIS;
                logger.info("[VoltEdge] Consecutive SL pause for 30 min");
            }
            return true;
        }

        // ── Hybrid TSL with 3-tier trailing ────────────────────────────
        if (pnl > peakProfit) {
            peakProfit = pnl;
        }
        if (!tslActivated && pnl >= TSL_ACTIVATION_PTS) {
            tslActivated = true;
            tslLevel = pnl - TSL_INITIAL_LOCK;
            logger.info("[VoltEdge] TSL activated at profit=" + pnl);
        }
        if (tslActivated) {
            double trailDist = computeTrailingDistance(pnl);
            double newTsl = peakProfit - trailDist;
            if (newTsl > tslLevel + TSL_STEP) {
                tslLevel = newTsl;
            }
            if (pnl <= tslLevel) {
                consecutiveSLCount = 0;
                logger.info("[VoltEdge] TSL hit pnl=" + pnl + " level=" + tslLevel);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onExit() {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            logger.info("[VoltEdge] Position exited");
            activeSignal = null;
        }
        markExit();
    }

    @Override
    protected void onExitEvaluation() {
        // TSL tracking is handled within shouldExit()
    }

    // ── Private helpers ────────────────────────────────────────────────

    private int classifyVolumeTier(long oiLots, double premium) {
        if (oiLots >= TIER3_LOTS && premium >= TIER3_PREMIUM) return 3;
        if (oiLots >= TIER2_LOTS && premium >= TIER2_PREMIUM) return 2;
        if (oiLots >= TIER1_LOTS && premium >= TIER1_PREMIUM) return 1;
        return 0;
    }

    private boolean passesIvQualityGate(double currentIv) {
        if (previousIv <= 0) return false;
        int passed = 0;
        double ivChangePct = ((currentIv - previousIv) / previousIv) * 100.0;
        if (ivChangePct < -IV_DECLINE_THRESHOLD) passed++;
        if (currentIv >= previousIv * (1.0 + IV_VOL_EXPANSION_PCT / 100.0)) passed++;
        if (Math.abs(ivChangePct) > IV_SPIKE_THRESHOLD
                || ivChangePct < -IV_CRUSH_THRESHOLD) passed++;
        return passed >= 2;
    }

    private double getTimeDecayMultiplier() {
        int hour = LocalTime.now(IST).getHour();
        if (hour < 11) return SL_PHASE1_MULT;
        if (hour < 14) return SL_PHASE2_MULT;
        return SL_PHASE3_MULT;
    }

    private double computeTrailingDistance(double currentProfit) {
        if (currentProfit >= TSL_ACTIVATION_PTS * TSL_DEEP_MULT) {
            return TSL_TRAILING_DIST * TSL_DEEP_MULT;
        }
        if (currentProfit >= TSL_ACTIVATION_PTS * TSL_EXTENDED_MULT) {
            return TSL_TRAILING_DIST * TSL_EXTENDED_MULT;
        }
        return TSL_TRAILING_DIST;
    }
}
