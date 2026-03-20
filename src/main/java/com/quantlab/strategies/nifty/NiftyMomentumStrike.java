package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.*;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * NiftyMomentumStrike -- Watchlist-based breakout BUY strategy for NIFTY.
 *
 * <h3>Overview:</h3>
 * <p>Creates a watchlist at ATM recording CE and PE baseline LTPs. On each
 * tick, monitors for a 30% breakout (LTP >= baseline * 1.30). When a
 * breakout is detected, places a single BUY leg on the breakout side.
 * Only one BUY order is allowed per cycle (CE or PE, whichever breaks
 * out first). The watchlist expires at 16:00 IST.</p>
 *
 * <h3>Entry:</h3>
 * <ol>
 *   <li>Create watchlist: record ATM CE LTP and PE LTP as baselines</li>
 *   <li>Each tick: check if CE LTP >= baseline * 1.30 or PE LTP >= baseline * 1.30</li>
 *   <li>On first breakout: place single BUY leg</li>
 * </ol>
 *
 * <h3>Exit:</h3>
 * <ul>
 *   <li>SL: 50% of entry premium</li>
 *   <li>Watchlist expiry: 16:00 IST</li>
 *   <li>Market close</li>
 * </ul>
 *
 * <h3>Constraints:</h3>
 * <ul>
 *   <li>Only one BUY per cycle</li>
 *   <li>Watchlist must be set before breakout monitoring</li>
 * </ul>
 */
public class NiftyMomentumStrike extends BaseStrategy {

    private static final String UNDERLYING = "NIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "currentWeek";
    private static final int MAX_RE_ENTRIES = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Breakout and SL ────────────────────────────────────────────────
    private static final double BREAKOUT_MULTIPLIER = 1.30;
    private static final double SL_PERCENT_OF_PREMIUM = 50.0;
    private static final int WATCHLIST_EXPIRE_HOUR = 16;
    private static final int WATCHLIST_EXPIRE_MINUTE = 0;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Runtime state ──────────────────────────────────────────────────
    private Signal activeSignal;
    private boolean watchlistCreated;
    private boolean breakoutExecuted;
    private int watchlistStrike;
    private double ceBaseline;
    private double peBaseline;
    private double entryPremium;
    private String entryOptionType;

    public NiftyMomentumStrike(OrderService orderService, MarketDataProvider marketData) {
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

        // Check watchlist expiry
        if (now.isAfter(LocalTime.of(WATCHLIST_EXPIRE_HOUR, WATCHLIST_EXPIRE_MINUTE))) {
            resetWatchlist();
            return;
        }

        // Create watchlist if not yet created
        if (!watchlistCreated) {
            createWatchlist();
            return;
        }

        // Already bought in this cycle
        if (breakoutExecuted) {
            return;
        }

        // Monitor for breakout
        String ceSymbol = marketData.resolveSymbol(UNDERLYING, watchlistStrike, "CE", TRADING_EXPIRY);
        String peSymbol = marketData.resolveSymbol(UNDERLYING, watchlistStrike, "PE", TRADING_EXPIRY);
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);

        if (ceQuote == null || peQuote == null) return;

        double ceLtp = ceQuote.getLtp();
        double peLtp = peQuote.getLtp();

        // Check CE breakout
        if (ceBaseline > 0 && ceLtp >= ceBaseline * BREAKOUT_MULTIPLIER) {
            executeBreakoutBuy(ceSymbol, "CE", watchlistStrike, ceLtp);
            return;
        }

        // Check PE breakout
        if (peBaseline > 0 && peLtp >= peBaseline * BREAKOUT_MULTIPLIER) {
            executeBreakoutBuy(peSymbol, "PE", watchlistStrike, peLtp);
        }
    }

    @Override
    protected boolean shouldExit() {
        if (activeSignal == null) return false;

        // Watchlist expiry
        LocalTime now = LocalTime.now(IST);
        if (now.isAfter(LocalTime.of(WATCHLIST_EXPIRE_HOUR, WATCHLIST_EXPIRE_MINUTE))) {
            logger.info("[MomentumStrike] Watchlist expired, exiting");
            return true;
        }

        // SL check: 50% of premium
        StrategyLeg leg = activeSignal.getLegs().get(0);
        OptionQuote quote = marketData.getOptionQuote(leg.getName());
        if (quote == null) return true;

        double slThreshold = entryPremium * (1.0 - SL_PERCENT_OF_PREMIUM / 100.0);
        if (quote.getLtp() <= slThreshold) {
            logger.info("[MomentumStrike] SL triggered, ltp=" + quote.getLtp()
                    + " threshold=" + slThreshold);
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
        resetWatchlist();
        logger.info("[MomentumStrike] Exited, type=" + entryOptionType);
    }

    @Override
    protected void onExitEvaluation() {
        // SL monitoring is handled in shouldExit()
    }

    // ── Watchlist management ───────────────────────────────────────────

    private void createWatchlist() {
        double spot = marketData.getSpotPrice(UNDERLYING);
        watchlistStrike = roundToStrike(spot);

        String ceSymbol = marketData.resolveSymbol(UNDERLYING, watchlistStrike, "CE", TRADING_EXPIRY);
        String peSymbol = marketData.resolveSymbol(UNDERLYING, watchlistStrike, "PE", TRADING_EXPIRY);
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);

        if (ceQuote == null || peQuote == null) return;

        ceBaseline = ceQuote.getLtp();
        peBaseline = peQuote.getLtp();
        watchlistCreated = true;
        breakoutExecuted = false;

        logger.info("[MomentumStrike] Watchlist created, strike=" + watchlistStrike
                + " ceBaseline=" + ceBaseline + " peBaseline=" + peBaseline);
    }

    private void resetWatchlist() {
        watchlistCreated = false;
        breakoutExecuted = false;
        ceBaseline = 0;
        peBaseline = 0;
        watchlistStrike = 0;
    }

    private void executeBreakoutBuy(String symbol, String optionType, int strike, double ltp) {
        StrategyLeg leg = StrategyLeg.builder()
                .name(symbol)
                .optionType(optionType)
                .side("BUY")
                .strike(strike)
                .quantity(1)
                .entryPrice(ltp)
                .currentPrice(ltp)
                .status("OPEN")
                .build();

        double spot = marketData.getSpotPrice(UNDERLYING);
        activeSignal = Signal.builder()
                .strategyId(0L).status("LIVE")
                .currentAtm(strike).baseIndexPrice(spot)
                .addLeg(leg)
                .build();

        orderService.placeEntryOrders(activeSignal);
        entryPremium = ltp;
        entryOptionType = optionType;
        breakoutExecuted = true;
        markEntry();

        double baseline = "CE".equals(optionType) ? ceBaseline : peBaseline;
        double breakoutPct = ((ltp - baseline) / baseline) * 100.0;
        logger.info("[MomentumStrike] Breakout BUY " + optionType
                + " strike=" + strike + " ltp=" + ltp
                + " breakout=" + String.format("%.1f", breakoutPct) + "%");
    }
}
