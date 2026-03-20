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
 * SENSEX Momentum Strike strategy (Option Buying).
 * <p>
 * Monitors a watchlist of strikes for breakout conditions. Entry is
 * triggered when an option's price surges beyond a 30% threshold from
 * its session baseline. Positions are managed with a 50% stop-loss
 * from peak premium. This is a pure directional buying strategy
 * designed to capture intraday momentum on SENSEX options.
 * <p>
 * <b>SENSEX-specific parameters:</b>
 * <ul>
 *   <li>Segment: BSEFO</li>
 *   <li>Strike interval: 200 pts</li>
 *   <li>Breakout threshold: 30% surge from baseline</li>
 *   <li>Stop-loss: 50% drawdown from peak</li>
 *   <li>Scan range: 5 strikes each side of ATM</li>
 *   <li>Max re-entries: 10, cooldown: 60s</li>
 * </ul>
 *
 * @see BaseStrategy
 */
public class SensexMomentumStrike extends BaseStrategy {

    // -- Index & structure --
    private static final String UNDERLYING = "SENSEX";
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int STRIKE_INTERVAL = 200;

    // -- Breakout parameters --
    private static final double BREAKOUT_THRESHOLD_PCT = 0.30;
    private static final double STOP_LOSS_FROM_PEAK_PCT = 0.50;

    // -- Watchlist scan range --
    private static final int SCAN_STRIKES_EACH_SIDE = 5;

    // -- Position sizing --
    private static final int ENTRY_LOTS = 1;

    // -- Re-entry --
    private static final int MAX_RE_ENTRIES = 10;
    private static final int COOLDOWN_SECONDS = 60;

    // -- Runtime state --
    private Signal activeSignal;
    private double peakPrice;
    private Instant lastEntryTime = Instant.EPOCH;
    private int reEntryCount;

    public SensexMomentumStrike(OrderService orderService, MarketDataProvider marketData) {
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
        BreakoutCandidate candidate = scanForBreakout(atmStrike);
        if (candidate == null) {
            return;
        }

        OptionQuote quote = marketData.getOptionQuote(candidate.symbol);
        if (quote == null || quote.getLtp() <= 0) {
            return;
        }

        StrategyLeg leg = StrategyLeg.builder()
                .name(candidate.symbol)
                .optionType(candidate.optionType)
                .side("BUY")
                .strike(candidate.strike)
                .quantity(ENTRY_LOTS)
                .entryPrice(quote.getLtp())
                .currentPrice(quote.getLtp())
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
        peakPrice = quote.getLtp();
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
        if (peakPrice <= 0) {
            return false;
        }

        double drawdownFromPeak = (peakPrice - currentPrice) / peakPrice;
        return drawdownFromPeak >= STOP_LOSS_FROM_PEAK_PCT;
    }

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            activeSignal = null;
        }
        peakPrice = 0.0;
    }

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) {
            return;
        }

        StrategyLeg leg = activeSignal.getLegs().get(0);
        OptionQuote quote = marketData.getOptionQuote(leg.getName());
        if (quote != null && quote.getLtp() > peakPrice) {
            peakPrice = quote.getLtp();
        }

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // -- Private helpers --

    private String buildOptionSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + TRADING_EXPIRY + "-" + strike + optionType;
    }

    private BreakoutCandidate scanForBreakout(int atmStrike) {
        for (int i = -SCAN_STRIKES_EACH_SIDE; i <= SCAN_STRIKES_EACH_SIDE; i++) {
            int strike = atmStrike + i * STRIKE_INTERVAL;

            for (String optType : new String[]{"CE", "PE"}) {
                String symbol = buildOptionSymbol(strike, optType);
                OptionQuote quote = marketData.getOptionQuote(symbol);

                if (quote == null || quote.getLtp() <= 0) {
                    continue;
                }

                double baseline = quote.getBid();
                if (baseline <= 0) {
                    baseline = quote.getLtp() * 0.85;
                }

                double surge = (quote.getLtp() - baseline) / baseline;
                if (surge >= BREAKOUT_THRESHOLD_PCT) {
                    return new BreakoutCandidate(symbol, optType, strike);
                }
            }
        }
        return null;
    }

    /**
     * Internal value holder for a detected breakout candidate.
     */
    private static final class BreakoutCandidate {
        final String symbol;
        final String optionType;
        final int strike;

        BreakoutCandidate(String symbol, String optionType, int strike) {
            this.symbol = symbol;
            this.optionType = optionType;
            this.strike = strike;
        }
    }
}
