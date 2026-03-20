package com.quantlab.strategies.sensex;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SENSEX ATM Accumulation strategy (VoltEdge).
 * <p>
 * Detects institutional accumulation near ATM strikes via volume-tier
 * analysis and IV behaviour. Enters deep ITM options (600 pts deep at
 * 200-pt strike intervals) to capture directional momentum with a
 * trailing stop-loss mechanism and IV-based exit filters.
 * <p>
 * <b>SENSEX-specific parameters:</b>
 * <ul>
 *   <li>Segment: BSEFO (BSE Futures &amp; Options)</li>
 *   <li>Strike interval: 200 pts (widest of all indices)</li>
 *   <li>Expiry: nextWeek (SENSEX weekly expiry)</li>
 *   <li>Deep ITM offset: 3 x 200 = 600 pts</li>
 * </ul>
 *
 * <b>Trailing Stop-Loss:</b> activates at +40 pts, locks 25 pts initial,
 * steps in 20-pt increments, trails at 20-pt distance. Fixed SL: 50 pts.
 *
 * <b>Volume Tiers:</b> T1=5lots/Rs.100, T2=10lots/Rs.200, T3=20lots/Rs.400
 *
 * <b>IV Filters:</b> decline &gt; 1.5%, expansion &ge; 70%, spike &gt; 40%,
 * crush &gt; 25%
 *
 * @see BaseStrategy
 */
public class SensexVoltEdge extends BaseStrategy {

    // -- Index & structure --
    private static final String UNDERLYING = "SENSEX";
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int STRIKE_INTERVAL = 200;

    // -- Trailing stop-loss parameters --
    private static final double TSL_ACTIVATION_PTS = 40.0;
    private static final double TSL_INITIAL_LOCK_PTS = 25.0;
    private static final double TSL_STEP_PTS = 20.0;
    private static final double TSL_TRAILING_DIST_PTS = 20.0;

    // -- Fixed stop-loss --
    private static final double FIXED_SL_PTS = 50.0;

    // -- Volume tiers --
    private static final int TIER1_LOTS = 5;
    private static final double TIER1_THRESHOLD = 100.0;
    private static final int TIER2_LOTS = 10;
    private static final double TIER2_THRESHOLD = 200.0;
    private static final int TIER3_LOTS = 20;
    private static final double TIER3_THRESHOLD = 400.0;

    // -- IV checks --
    private static final double IV_DECLINE_THRESHOLD = 0.015;
    private static final double VOL_EXPANSION_THRESHOLD = 0.70;
    private static final double IV_SPIKE_THRESHOLD = 0.40;
    private static final double IV_CRUSH_THRESHOLD = 0.25;

    // -- Re-entry --
    private static final int MAX_RE_ENTRIES = 10;
    private static final int COOLDOWN_SECONDS = 300;

    // -- Deep ITM offset (3 x strikeInterval) --
    private static final int DEEP_ITM_OFFSET = 3 * STRIKE_INTERVAL;

    // -- Runtime state --
    private double entryPrice;
    private double peakPnl;
    private double trailingStopLevel;
    private boolean tslActivated;
    private double baselineIv;
    private Signal activeSignal;
    private Instant lastEntryTime = Instant.EPOCH;
    private int reEntryCount;

    public SensexVoltEdge(OrderService orderService, MarketDataProvider marketData) {
        super(buildConfig(), orderService, marketData);
    }

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .underlying(UNDERLYING)
                .exchange(EXCHANGE)
                .segment(SEGMENT)
                .strikeInterval(STRIKE_INTERVAL)
                .tradingExpiry(TRADING_EXPIRY)
                .maxReEntries(MAX_RE_ENTRIES)
                .exitCooldownSeconds(COOLDOWN_SECONDS)
                .defaultStopLoss(FIXED_SL_PTS)
                .build();
    }

    @Override
    protected void onEntry(long strategyId) {
        if (reEntryCount >= MAX_RE_ENTRIES) {
            return;
        }
        if (Duration.between(lastEntryTime, Instant.now()).getSeconds() < COOLDOWN_SECONDS) {
            return;
        }
        if (!marketData.isDataFresh(UNDERLYING, 5)) {
            return;
        }

        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) {
            return;
        }

        int atmStrike = marketData.getATM(UNDERLYING, spot, TRADING_EXPIRY);
        String ceSymbol = buildOptionSymbol(atmStrike, "CE");
        String peSymbol = buildOptionSymbol(atmStrike, "PE");
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);

        if (ceQuote == null || peQuote == null) {
            return;
        }

        double atmVolume = ceQuote.getVolume() + peQuote.getVolume();
        if (!detectAccumulation(atmVolume, ceQuote, peQuote)) {
            return;
        }

        boolean bullish = ceQuote.getVolume() > peQuote.getVolume();
        int deepStrike = bullish ? atmStrike - DEEP_ITM_OFFSET : atmStrike + DEEP_ITM_OFFSET;
        String optionType = bullish ? "CE" : "PE";
        String deepSymbol = buildOptionSymbol(deepStrike, optionType);

        OptionQuote deepQuote = marketData.getOptionQuote(deepSymbol);
        if (deepQuote == null || deepQuote.getLtp() <= 0) {
            return;
        }

        int lots = determineVolumeTier(atmVolume);

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

        entryPrice = deepQuote.getLtp();
        peakPnl = 0.0;
        trailingStopLevel = entryPrice - FIXED_SL_PTS;
        tslActivated = false;
        baselineIv = deepQuote.getIv();
        lastEntryTime = Instant.now();
        reEntryCount++;
    }

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) {
            return false;
        }

        StrategyLeg leg = activeSignal.getLegs().get(0);
        OptionQuote quote = marketData.getOptionQuote(leg.getName());
        if (quote == null) {
            return true;
        }

        double currentPrice = quote.getLtp();
        if (currentPrice <= trailingStopLevel) {
            return true;
        }

        return checkIvExit(quote);
    }

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            activeSignal = null;
        }
        tslActivated = false;
    }

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) {
            return;
        }

        StrategyLeg leg = activeSignal.getLegs().get(0);
        OptionQuote quote = marketData.getOptionQuote(leg.getName());
        if (quote == null) {
            return;
        }

        double currentPrice = quote.getLtp();
        double pnl = currentPrice - entryPrice;

        if (pnl > peakPnl) {
            peakPnl = pnl;
        }

        if (!tslActivated && pnl >= TSL_ACTIVATION_PTS) {
            tslActivated = true;
            trailingStopLevel = entryPrice + TSL_INITIAL_LOCK_PTS;
        }

        if (tslActivated) {
            double newTrail = currentPrice - TSL_TRAILING_DIST_PTS;
            double stepLock = entryPrice + TSL_INITIAL_LOCK_PTS
                    + Math.floor((pnl - TSL_ACTIVATION_PTS) / TSL_STEP_PTS) * TSL_STEP_PTS;
            double candidate = Math.max(newTrail, stepLock);
            if (candidate > trailingStopLevel) {
                trailingStopLevel = candidate;
            }
        }

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // -- Private helpers --

    private String buildOptionSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + TRADING_EXPIRY + "-" + strike + optionType;
    }

    private boolean detectAccumulation(double atmVolume, OptionQuote ce, OptionQuote pe) {
        if (atmVolume < TIER1_THRESHOLD) {
            return false;
        }

        double avgIv = (ce.getIv() + pe.getIv()) / 2.0;
        if (baselineIv > 0) {
            double ivChange = (avgIv - baselineIv) / baselineIv;
            if (ivChange < -IV_DECLINE_THRESHOLD) {
                return false;
            }
        }

        double volRatio = Math.max(ce.getVolume(), pe.getVolume())
                / (double) Math.max(1, Math.min(ce.getVolume(), pe.getVolume()));
        if (volRatio < VOL_EXPANSION_THRESHOLD) {
            return false;
        }

        return true;
    }

    private boolean checkIvExit(OptionQuote quote) {
        if (baselineIv <= 0) {
            return false;
        }
        double ivChange = (quote.getIv() - baselineIv) / baselineIv;
        return ivChange > IV_SPIKE_THRESHOLD || ivChange < -IV_CRUSH_THRESHOLD;
    }

    private int determineVolumeTier(double volume) {
        if (volume >= TIER3_THRESHOLD) {
            return TIER3_LOTS;
        } else if (volume >= TIER2_THRESHOLD) {
            return TIER2_LOTS;
        } else {
            return TIER1_LOTS;
        }
    }
}
