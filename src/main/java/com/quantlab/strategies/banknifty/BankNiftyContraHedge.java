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
 * BANKNIFTY Inverted Delta Hedge Strategy (ContraHedge / Ulta Delta).
 * <p>
 * Applies an inverted hedge assignment: where a conventional hedge buys
 * the same-side OTM option, this strategy buys the <em>opposite-side</em>
 * OTM option as the hedge. The core short straddle is protected by a
 * contrarian wing that profits from mean-reversion whipsaws.
 * <p>
 * Structure (4 legs):
 * <ul>
 *   <li>Sell ATM CE</li>
 *   <li>Sell ATM PE</li>
 *   <li>Buy OTM PE (inverted CE hedge — opposite side)</li>
 *   <li>Buy OTM CE (inverted PE hedge — opposite side)</li>
 * </ul>
 * <p>
 * The inverted wings are placed at 2x strike interval OTM on the
 * opposite side, capturing premium if the market reverses after an
 * initial directional move.
 */
public class BankNiftyContraHedge extends BaseStrategy {

    private static final Logger log = Logger.getLogger(BankNiftyContraHedge.class.getName());

    // ── Index constants ─────────────────────────────────────────────────
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT    = "NSEFO";
    private static final String EXPIRY     = "currentMonth";
    private static final int    STRIKE_INTERVAL = 100;

    // ── Re-entry and cooldown ───────────────────────────────────────────
    private static final int MAX_RE_ENTRIES   = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Hedge parameters ────────────────────────────────────────────────
    private static final int    INVERTED_WING_MULTIPLIER = 2;
    private static final double MAX_HEDGE_COST_PCT       = 0.20;
    private static final double PORTFOLIO_SL_PCT         = 0.35;

    // ── Runtime state ───────────────────────────────────────────────────
    private Signal activeSignal;
    private double netCreditCollected;

    public BankNiftyContraHedge(OrderService orderService, MarketDataProvider marketData) {
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
        int invertedPeStrike = atmStrike - (INVERTED_WING_MULTIPLIER * STRIKE_INTERVAL);
        int invertedCeStrike = atmStrike + (INVERTED_WING_MULTIPLIER * STRIKE_INTERVAL);

        String atmCeSym   = buildSymbol(atmStrike, "CE");
        String atmPeSym   = buildSymbol(atmStrike, "PE");
        String hedgePeSym = buildSymbol(invertedPeStrike, "PE");
        String hedgeCeSym = buildSymbol(invertedCeStrike, "CE");

        OptionQuote atmCeQ   = marketData.getOptionQuote(atmCeSym);
        OptionQuote atmPeQ   = marketData.getOptionQuote(atmPeSym);
        OptionQuote hedgePeQ = marketData.getOptionQuote(hedgePeSym);
        OptionQuote hedgeCeQ = marketData.getOptionQuote(hedgeCeSym);
        if (atmCeQ == null || atmPeQ == null || hedgePeQ == null || hedgeCeQ == null) return;

        double straddlePremium = atmCeQ.getLtp() + atmPeQ.getLtp();
        double hedgeCost = hedgePeQ.getLtp() + hedgeCeQ.getLtp();

        if (straddlePremium > 0 && (hedgeCost / straddlePremium) > MAX_HEDGE_COST_PCT) {
            log.info("[" + strategyId + "] Inverted hedge cost excessive: " + hedgeCost);
            return;
        }

        netCreditCollected = straddlePremium - hedgeCost;

        List<StrategyLeg> legs = new ArrayList<>(4);
        legs.add(buildLeg(atmCeSym,   "CE", "SELL", atmStrike,       atmCeQ));
        legs.add(buildLeg(atmPeSym,   "PE", "SELL", atmStrike,       atmPeQ));
        legs.add(buildLeg(hedgePeSym, "PE", "BUY",  invertedPeStrike, hedgePeQ));
        legs.add(buildLeg(hedgeCeSym, "CE", "BUY",  invertedCeStrike, hedgeCeQ));

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
        log.info("[" + strategyId + "] ContraHedge entry: ATM=" + atmStrike
                + " inverted wings=" + INVERTED_WING_MULTIPLIER + "x"
                + " credit=" + netCreditCollected);
    }

    // ── Exit evaluation ─────────────────────────────────────────────────

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) return;

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // ── Should exit ─────────────────────────────────────────────────────

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) return false;

        double totalPnl = computePortfolioPnl();
        double slLimit = -(netCreditCollected * PORTFOLIO_SL_PCT);

        if (totalPnl <= slLimit) {
            log.info("[" + strategyId + "] ContraHedge SL hit: pnl=" + totalPnl
                    + " limit=" + slLimit);
            return true;
        }
        return false;
    }

    // ── Exit ────────────────────────────────────────────────────────────

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal == null) return;

        orderService.placeExitOrders(activeSignal);
        log.info("[" + strategyId + "] ContraHedge exit: pnl="
                + computePortfolioPnl());
        activeSignal = null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private double computePortfolioPnl() {
        double pnl = 0;
        for (StrategyLeg leg : activeSignal.getLegs()) {
            if (!leg.isOpen()) continue;
            OptionQuote quote = marketData.getOptionQuote(leg.getName());
            if (quote == null) continue;
            double diff = quote.getLtp() - leg.getEntryPrice();
            pnl += "SELL".equals(leg.getSide())
                    ? -diff * leg.getQuantity()
                    : diff * leg.getQuantity();
        }
        return pnl;
    }

    private StrategyLeg buildLeg(String symbol, String optionType, String side,
                                 int strike, OptionQuote quote) {
        return StrategyLeg.builder()
                .name(symbol).optionType(optionType).side(side)
                .strike(strike).quantity(1)
                .entryPrice(quote.getLtp()).currentPrice(quote.getLtp())
                .status("OPEN").build();
    }

    private String buildSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + EXPIRY + "-" + strike + optionType;
    }
}
