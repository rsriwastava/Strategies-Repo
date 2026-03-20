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
 * BANKNIFTY Paired Straddle Strategy (PairStrike / Jodi).
 * <p>
 * Sells two adjacent ATM straddles (Jodi = pair), each offset by one
 * strike interval from the computed ATM. When the underlying moves by
 * one full strike interval (100 pts), the farther straddle is adjusted
 * (exited and re-entered) to remain centred around the new ATM.
 * <p>
 * Structure (4 legs at entry):
 * <ul>
 *   <li>Straddle A: Sell CE + Sell PE at ATM</li>
 *   <li>Straddle B: Sell CE + Sell PE at ATM + 100</li>
 * </ul>
 * <p>
 * Mid-trade adjustment:
 * When spot moves > 100 pts from pair centre, the entire position is
 * closed and re-opened at updated ATM boundaries.
 */
public class BankNiftyPairStrike extends BaseStrategy {

    private static final Logger log = Logger.getLogger(BankNiftyPairStrike.class.getName());

    // ── Index constants ─────────────────────────────────────────────────
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT    = "NSEFO";
    private static final String EXPIRY     = "currentMonth";
    private static final int    STRIKE_INTERVAL = 100;

    // ── Re-entry and cooldown ───────────────────────────────────────────
    private static final int MAX_RE_ENTRIES   = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Pair parameters ─────────────────────────────────────────────────
    private static final double ADJUSTMENT_TRIGGER_PTS = 100.0;
    private static final double PORTFOLIO_SL_PCT       = 0.30;

    // ── Runtime state ───────────────────────────────────────────────────
    private Signal activeSignal;
    private int pairCentreStrike;
    private double netCreditCollected;

    public BankNiftyPairStrike(OrderService orderService, MarketDataProvider marketData) {
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

        pairCentreStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);
        int strikeA = pairCentreStrike;
        int strikeB = pairCentreStrike + STRIKE_INTERVAL;

        List<StrategyLeg> legs = new ArrayList<>(4);
        double credit = 0;
        credit += addStraddleLegs(legs, strikeA);
        credit += addStraddleLegs(legs, strikeB);
        netCreditCollected = credit;

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(pairCentreStrike)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
        log.info("[" + strategyId + "] PairStrike entry: A=" + strikeA
                + " B=" + strikeB + " credit=" + netCreditCollected);
    }

    // ── Exit evaluation (adjustment) ────────────────────────────────────

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) return;

        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) return;

        double drift = Math.abs(spot - pairCentreStrike);
        if (drift >= ADJUSTMENT_TRIGGER_PTS) {
            log.info("[" + strategyId + "] PairStrike adjustment: spot=" + spot
                    + " oldCentre=" + pairCentreStrike);
            orderService.placeExitOrders(activeSignal);

            pairCentreStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);
            int strikeA = pairCentreStrike;
            int strikeB = pairCentreStrike + STRIKE_INTERVAL;

            List<StrategyLeg> newLegs = new ArrayList<>(4);
            double credit = 0;
            credit += addStraddleLegs(newLegs, strikeA);
            credit += addStraddleLegs(newLegs, strikeB);
            netCreditCollected = credit;

            activeSignal = Signal.builder()
                    .strategyId(strategyId)
                    .status("LIVE")
                    .currentAtm(pairCentreStrike)
                    .baseIndexPrice(spot)
                    .legs(newLegs)
                    .build();

            orderService.placeEntryOrders(activeSignal);
            log.info("[" + strategyId + "] PairStrike adjusted: A=" + strikeA
                    + " B=" + strikeB);
        }

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
            log.info("[" + strategyId + "] PairStrike SL hit: pnl=" + totalPnl);
            return true;
        }
        return false;
    }

    // ── Exit ────────────────────────────────────────────────────────────

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal == null) return;

        orderService.placeExitOrders(activeSignal);
        log.info("[" + strategyId + "] PairStrike exit: pnl="
                + computePortfolioPnl());
        activeSignal = null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private double addStraddleLegs(List<StrategyLeg> legs, int strike) {
        String ceSymbol = buildSymbol(strike, "CE");
        String peSymbol = buildSymbol(strike, "PE");
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        double ceLtp = (ceQuote != null) ? ceQuote.getLtp() : 0;
        double peLtp = (peQuote != null) ? peQuote.getLtp() : 0;

        legs.add(StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(strike).quantity(1)
                .entryPrice(ceLtp).currentPrice(ceLtp)
                .status("OPEN").build());
        legs.add(StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(strike).quantity(1)
                .entryPrice(peLtp).currentPrice(peLtp)
                .status("OPEN").build());

        return ceLtp + peLtp;
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

    private String buildSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + EXPIRY + "-" + strike + optionType;
    }
}
