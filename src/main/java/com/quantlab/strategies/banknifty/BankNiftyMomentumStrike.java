package com.quantlab.strategies.banknifty;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * BANKNIFTY Watchlist-Based Momentum Breakout Strategy (MomentumStrike).
 * <p>
 * Monitors a predefined watchlist of OTM options for breakout signals.
 * A breakout is confirmed when an option's premium surges more than
 * 30% from its session low. Entry is on the buying side; exit is
 * triggered by a 50% retracement from the post-breakout high (trailing
 * stop) or a fixed SL of 25% from entry.
 * <p>
 * Key parameters:
 * <ul>
 *   <li>Breakout threshold: 30% premium surge from session low</li>
 *   <li>Trailing SL: 50% retracement from peak</li>
 *   <li>Fixed SL: 25% of entry premium</li>
 *   <li>Watchlist: ATM +/- 5 strikes (CE and PE)</li>
 *   <li>Max simultaneous positions: 2</li>
 * </ul>
 */
public class BankNiftyMomentumStrike extends BaseStrategy {

    private static final Logger log = Logger.getLogger(BankNiftyMomentumStrike.class.getName());

    // ── Index constants ─────────────────────────────────────────────────
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT    = "NSEFO";
    private static final String EXPIRY     = "currentMonth";
    private static final int    STRIKE_INTERVAL = 100;

    // ── Re-entry and cooldown ───────────────────────────────────────────
    private static final int MAX_RE_ENTRIES   = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Breakout parameters ─────────────────────────────────────────────
    private static final double BREAKOUT_THRESHOLD_PCT  = 0.30;
    private static final double TRAILING_SL_RETRACE_PCT = 0.50;
    private static final double FIXED_SL_PCT            = 0.25;

    // ── Watchlist scan range ────────────────────────────────────────────
    private static final int WATCHLIST_RANGE = 5;
    private static final int MAX_POSITIONS   = 2;

    // ── Runtime state ───────────────────────────────────────────────────
    private final List<ActivePosition> positions = new ArrayList<>();
    private final Map<String, Double> sessionLows = new HashMap<>();
    private boolean hasActivePositions;

    public BankNiftyMomentumStrike(OrderService orderService, MarketDataProvider marketData) {
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
        if (positions.size() >= MAX_POSITIONS) return;

        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) return;

        int atmStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);

        for (int i = -WATCHLIST_RANGE; i <= WATCHLIST_RANGE; i++) {
            if (positions.size() >= MAX_POSITIONS) break;

            for (String optionType : new String[]{"CE", "PE"}) {
                if (positions.size() >= MAX_POSITIONS) break;

                int strike = atmStrike + (i * STRIKE_INTERVAL);
                String symbol = buildSymbol(strike, optionType);

                if (isAlreadyHeld(symbol)) continue;

                OptionQuote quote = marketData.getOptionQuote(symbol);
                if (quote == null) continue;

                double ltp = quote.getLtp();
                sessionLows.putIfAbsent(symbol, ltp);
                double sessionLow = sessionLows.get(symbol);

                if (ltp < sessionLow) {
                    sessionLows.put(symbol, ltp);
                    continue;
                }

                if (sessionLow > 0) {
                    double surgePct = (ltp - sessionLow) / sessionLow;
                    if (surgePct >= BREAKOUT_THRESHOLD_PCT) {
                        enterBreakout(strategyId, symbol, optionType, strike,
                                spot, quote);
                    }
                }
            }
        }

        hasActivePositions = !positions.isEmpty();
    }

    // ── Exit evaluation ─────────────────────────────────────────────────

    @Override
    protected void onExitEvaluation(long strategyId) {
        List<ActivePosition> toRemove = new ArrayList<>();

        for (ActivePosition pos : positions) {
            OptionQuote quote = marketData.getOptionQuote(pos.symbol);
            if (quote == null) continue;

            double currentPrice = quote.getLtp();
            if (currentPrice > pos.peakPrice) {
                pos.peakPrice = currentPrice;
            }

            double retraceFromPeak = (pos.peakPrice - currentPrice) / pos.peakPrice;
            double lossFromEntry = (pos.entryPrice - currentPrice) / pos.entryPrice;

            if (retraceFromPeak >= TRAILING_SL_RETRACE_PCT) {
                log.info("[" + strategyId + "] Trailing SL hit for " + pos.symbol
                        + " retrace=" + (retraceFromPeak * 100) + "%");
                orderService.placeExitOrders(pos.signal);
                toRemove.add(pos);
            } else if (lossFromEntry >= FIXED_SL_PCT) {
                log.info("[" + strategyId + "] Fixed SL hit for " + pos.symbol
                        + " loss=" + (lossFromEntry * 100) + "%");
                orderService.placeExitOrders(pos.signal);
                toRemove.add(pos);
            }
        }

        positions.removeAll(toRemove);
        hasActivePositions = !positions.isEmpty();
    }

    // ── Should exit ─────────────────────────────────────────────────────

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        return positions.isEmpty() && hasActivePositions;
    }

    // ── Exit ────────────────────────────────────────────────────────────

    @Override
    protected void onExit(long strategyId) {
        for (ActivePosition pos : positions) {
            orderService.placeExitOrders(pos.signal);
        }
        positions.clear();
        sessionLows.clear();
        hasActivePositions = false;
        log.info("[" + strategyId + "] MomentumStrike full exit");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void enterBreakout(long strategyId, String symbol, String optionType,
                               int strike, double spot, OptionQuote quote) {
        int atmStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);

        StrategyLeg leg = StrategyLeg.builder()
                .name(symbol)
                .optionType(optionType)
                .side("BUY")
                .strike(strike)
                .quantity(1)
                .entryPrice(quote.getLtp())
                .currentPrice(quote.getLtp())
                .status("OPEN")
                .build();

        Signal signal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .addLeg(leg)
                .build();

        orderService.placeEntryOrders(signal);
        positions.add(new ActivePosition(symbol, quote.getLtp(), signal));
        log.info("[" + strategyId + "] MomentumStrike breakout entry: " + symbol
                + " price=" + quote.getLtp());
    }

    private boolean isAlreadyHeld(String symbol) {
        for (ActivePosition pos : positions) {
            if (pos.symbol.equals(symbol)) return true;
        }
        return false;
    }

    private String buildSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + EXPIRY + "-" + strike + optionType;
    }

    // ── Inner position tracker ──────────────────────────────────────────

    private static final class ActivePosition {
        final String symbol;
        final double entryPrice;
        final Signal signal;
        double peakPrice;

        ActivePosition(String symbol, double entryPrice, Signal signal) {
            this.symbol = symbol;
            this.entryPrice = entryPrice;
            this.signal = signal;
            this.peakPrice = entryPrice;
        }
    }
}
