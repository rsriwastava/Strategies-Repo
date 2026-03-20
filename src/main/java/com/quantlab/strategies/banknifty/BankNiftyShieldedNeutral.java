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
 * BANKNIFTY Delta-Neutral Hedged Straddle Strategy (ShieldedNeutral).
 * <p>
 * Sells an ATM straddle (2 legs) and simultaneously buys protective OTM
 * wings (2 legs) to cap tail risk, forming a 4-leg iron butterfly. The
 * hedge width is scaled by DTE: wider wings when time allows, tighter
 * as expiry approaches.
 * <p>
 * Key parameters:
 * <ul>
 *   <li>Core: ATM CE sell + ATM PE sell</li>
 *   <li>Hedge: OTM CE buy + OTM PE buy</li>
 *   <li>Wing width scales with DTE (base = 3x interval, min = 1x interval)</li>
 *   <li>Max hedge cost: 15% of collected straddle premium</li>
 *   <li>Portfolio SL: 40% of net credit</li>
 * </ul>
 */
public class BankNiftyShieldedNeutral extends BaseStrategy {

    private static final Logger log = Logger.getLogger(BankNiftyShieldedNeutral.class.getName());

    // ── Index constants ─────────────────────────────────────────────────
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT    = "NSEFO";
    private static final String EXPIRY     = "currentMonth";
    private static final int    STRIKE_INTERVAL = 100;

    // ── Re-entry and cooldown ───────────────────────────────────────────
    private static final int MAX_RE_ENTRIES   = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Hedge parameters ────────────────────────────────────────────────
    private static final int    BASE_WING_MULTIPLIER = 3;
    private static final int    MIN_WING_MULTIPLIER  = 1;
    private static final double MAX_HEDGE_COST_PCT   = 0.15;
    private static final double PORTFOLIO_SL_PCT     = 0.40;

    // ── DTE scaling ─────────────────────────────────────────────────────
    private static final int    DTE_FRESH_SECONDS    = 5;
    private static final int    DTE_SCALE_CUTOFF     = 7;

    // ── Runtime state ───────────────────────────────────────────────────
    private Signal activeSignal;
    private double netCreditCollected;

    public BankNiftyShieldedNeutral(OrderService orderService, MarketDataProvider marketData) {
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
        int wingMultiplier = computeWingMultiplier();

        int ceHedgeStrike = atmStrike + (wingMultiplier * STRIKE_INTERVAL);
        int peHedgeStrike = atmStrike - (wingMultiplier * STRIKE_INTERVAL);

        String atmCeSym   = buildSymbol(atmStrike, "CE");
        String atmPeSym   = buildSymbol(atmStrike, "PE");
        String hedgeCeSym = buildSymbol(ceHedgeStrike, "CE");
        String hedgePeSym = buildSymbol(peHedgeStrike, "PE");

        OptionQuote atmCeQ   = marketData.getOptionQuote(atmCeSym);
        OptionQuote atmPeQ   = marketData.getOptionQuote(atmPeSym);
        OptionQuote hedgeCeQ = marketData.getOptionQuote(hedgeCeSym);
        OptionQuote hedgePeQ = marketData.getOptionQuote(hedgePeSym);
        if (atmCeQ == null || atmPeQ == null || hedgeCeQ == null || hedgePeQ == null) return;

        double straddlePremium = atmCeQ.getLtp() + atmPeQ.getLtp();
        double hedgeCost = hedgeCeQ.getLtp() + hedgePeQ.getLtp();

        if (straddlePremium > 0 && (hedgeCost / straddlePremium) > MAX_HEDGE_COST_PCT) {
            log.info("[" + strategyId + "] Hedge cost too high: "
                    + hedgeCost + " vs straddle=" + straddlePremium);
            return;
        }

        netCreditCollected = straddlePremium - hedgeCost;

        List<StrategyLeg> legs = new ArrayList<>(4);
        legs.add(buildLeg(atmCeSym,   "CE", "SELL", atmStrike,     atmCeQ));
        legs.add(buildLeg(atmPeSym,   "PE", "SELL", atmStrike,     atmPeQ));
        legs.add(buildLeg(hedgeCeSym, "CE", "BUY",  ceHedgeStrike, hedgeCeQ));
        legs.add(buildLeg(hedgePeSym, "PE", "BUY",  peHedgeStrike, hedgePeQ));

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
        log.info("[" + strategyId + "] ShieldedNeutral entry: ATM=" + atmStrike
                + " wings=" + wingMultiplier + "x credit=" + netCreditCollected);
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
            log.info("[" + strategyId + "] Portfolio SL hit: pnl=" + totalPnl
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
        log.info("[" + strategyId + "] ShieldedNeutral exit: pnl="
                + computePortfolioPnl());
        activeSignal = null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private int computeWingMultiplier() {
        boolean fresh = marketData.isDataFresh(UNDERLYING, DTE_FRESH_SECONDS);
        return fresh ? BASE_WING_MULTIPLIER : MIN_WING_MULTIPLIER;
    }

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
