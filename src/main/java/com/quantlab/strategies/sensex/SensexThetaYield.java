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

/**
 * SENSEX Theta Yield strategy (ThetaEdge / IV-IR Arbitrage).
 * <p>
 * Sells options with favourable theta-to-IV ratios, selecting strikes
 * where time decay outpaces implied-volatility risk. Employs delta
 * thresholds (0.65 standard, 0.70 near expiry) and an anti-churn
 * filter. Exits via a 5-trigger IV breach with 2-consecutive
 * confirmation requirement.
 * <p>
 * <b>SENSEX-specific parameters:</b>
 * <ul>
 *   <li>Segment: BSEFO</li>
 *   <li>Strike interval: 200 pts</li>
 *   <li>Expiry: nextWeek</li>
 *   <li>Max re-entries: 10, cooldown: 60s</li>
 * </ul>
 *
 * @see BaseStrategy
 */
public class SensexThetaYield extends BaseStrategy {

    // -- Index & structure --
    private static final String UNDERLYING = "SENSEX";
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int STRIKE_INTERVAL = 200;

    // -- Delta thresholds --
    private static final double DELTA_THRESHOLD_STANDARD = 0.65;
    private static final double DELTA_THRESHOLD_EXPIRY = 0.70;
    private static final int EXPIRY_DTE_CUTOFF = 2;

    // -- Theta/IV selection --
    private static final double MIN_THETA_IV_RATIO = 0.05;
    private static final int STRIKE_SCAN_RANGE = 5;

    // -- Anti-churn --
    private static final long MIN_HOLD_SECONDS = 120L;

    // -- IV exit: 5-trigger with 2-consecutive --
    private static final int IV_TRIGGER_LIMIT = 5;
    private static final int IV_CONSECUTIVE_REQUIRED = 2;
    private static final double IV_EXIT_DEVIATION = 0.15;

    // -- Re-entry --
    private static final int MAX_RE_ENTRIES = 10;
    private static final int COOLDOWN_SECONDS = 60;

    // -- Runtime state --
    private Signal activeSignal;
    private double entryIvCe;
    private double entryIvPe;
    private int ivBreachCount;
    private int consecutiveBreaches;
    private Instant positionOpenTime;
    private Instant lastEntryTime = Instant.EPOCH;
    private int reEntryCount;

    public SensexThetaYield(OrderService orderService, MarketDataProvider marketData) {
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
        double deltaThreshold = isDteNearExpiry() ? DELTA_THRESHOLD_EXPIRY : DELTA_THRESHOLD_STANDARD;

        int bestCeStrike = findBestThetaStrike(atmStrike, "CE", deltaThreshold);
        int bestPeStrike = findBestThetaStrike(atmStrike, "PE", deltaThreshold);

        if (bestCeStrike == 0 || bestPeStrike == 0) {
            return;
        }

        String ceSymbol = buildOptionSymbol(bestCeStrike, "CE");
        String peSymbol = buildOptionSymbol(bestPeStrike, "PE");
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);

        if (ceQuote == null || peQuote == null) {
            return;
        }

        StrategyLeg ceLeg = StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(bestCeStrike).quantity(1)
                .entryPrice(ceQuote.getLtp()).currentPrice(ceQuote.getLtp())
                .status("OPEN").build();

        StrategyLeg peLeg = StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(bestPeStrike).quantity(1)
                .entryPrice(peQuote.getLtp()).currentPrice(peQuote.getLtp())
                .status("OPEN").build();

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .addLeg(ceLeg).addLeg(peLeg)
                .build();

        orderService.placeEntryOrders(activeSignal);

        entryIvCe = ceQuote.getIv();
        entryIvPe = peQuote.getIv();
        ivBreachCount = 0;
        consecutiveBreaches = 0;
        positionOpenTime = Instant.now();
        lastEntryTime = Instant.now();
        reEntryCount++;
    }

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) {
            return false;
        }
        if (positionOpenTime != null
                && Duration.between(positionOpenTime, Instant.now()).getSeconds() < MIN_HOLD_SECONDS) {
            return false;
        }

        return ivBreachCount >= IV_TRIGGER_LIMIT
                && consecutiveBreaches >= IV_CONSECUTIVE_REQUIRED;
    }

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            activeSignal = null;
        }
        ivBreachCount = 0;
        consecutiveBreaches = 0;
    }

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) {
            return;
        }

        boolean breached = false;
        for (StrategyLeg leg : activeSignal.getLegs()) {
            OptionQuote quote = marketData.getOptionQuote(leg.getName());
            if (quote == null) {
                continue;
            }
            double baseIv = "CE".equals(leg.getOptionType()) ? entryIvCe : entryIvPe;
            if (baseIv > 0) {
                double deviation = Math.abs(quote.getIv() - baseIv) / baseIv;
                if (deviation > IV_EXIT_DEVIATION) {
                    breached = true;
                }
            }
        }

        if (breached) {
            ivBreachCount++;
            consecutiveBreaches++;
        } else {
            consecutiveBreaches = 0;
        }

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // -- Private helpers --

    private String buildOptionSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + TRADING_EXPIRY + "-" + strike + optionType;
    }

    private boolean isDteNearExpiry() {
        // Approximate DTE check via synthetic price staleness heuristic
        double synth = marketData.getSyntheticPrice(UNDERLYING);
        double spot = marketData.getSpotPrice(UNDERLYING);
        // When synthetic converges to spot, expiry is near
        return !Double.isNaN(synth) && !Double.isNaN(spot)
                && Math.abs(synth - spot) < STRIKE_INTERVAL * 0.5;
    }

    private int findBestThetaStrike(int atmStrike, String optionType, double deltaThreshold) {
        int bestStrike = 0;
        double bestRatio = 0.0;

        for (int i = -STRIKE_SCAN_RANGE; i <= STRIKE_SCAN_RANGE; i++) {
            int strike = atmStrike + i * STRIKE_INTERVAL;
            String symbol = buildOptionSymbol(strike, optionType);
            OptionQuote quote = marketData.getOptionQuote(symbol);

            if (quote == null || quote.getIv() <= 0 || Math.abs(quote.getTheta()) < 0.01) {
                continue;
            }

            double absDelta = Math.abs(marketData.getDelta(symbol));
            if (Double.isNaN(absDelta) || absDelta > deltaThreshold) {
                continue;
            }

            double ratio = Math.abs(quote.getTheta()) / quote.getIv();
            if (ratio > MIN_THETA_IV_RATIO && ratio > bestRatio) {
                bestRatio = ratio;
                bestStrike = strike;
            }
        }

        return bestStrike;
    }
}
