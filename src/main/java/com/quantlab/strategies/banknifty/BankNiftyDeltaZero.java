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
 * BANKNIFTY Delta-Neutral Ratio Straddle Strategy.
 * <p>
 * Sells a 2-leg ATM straddle (1 CE + 1 PE) with lot ratios computed to
 * achieve net-zero portfolio delta at entry. The ratio is recalculated on
 * each exit-evaluation tick and legs are adjusted when the net delta
 * exceeds the rebalance threshold.
 * <p>
 * Key parameters:
 * <ul>
 *   <li>Legs: 2 (ATM CE sell + ATM PE sell)</li>
 *   <li>Delta rebalance threshold: 0.10</li>
 *   <li>Max position gamma: 0.05</li>
 *   <li>SL per leg: 30% of entry premium</li>
 * </ul>
 */
public class BankNiftyDeltaZero extends BaseStrategy {

    private static final Logger log = Logger.getLogger(BankNiftyDeltaZero.class.getName());

    // ── Index constants ─────────────────────────────────────────────────
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT    = "NSEFO";
    private static final String EXPIRY     = "currentMonth";
    private static final int    STRIKE_INTERVAL = 100;

    // ── Re-entry and cooldown ───────────────────────────────────────────
    private static final int MAX_RE_ENTRIES   = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Delta-neutral parameters ────────────────────────────────────────
    private static final double DELTA_REBALANCE_THRESHOLD = 0.10;
    private static final double MAX_GAMMA_EXPOSURE        = 0.05;
    private static final double LEG_SL_PCT                = 0.30;

    // ── Runtime state ───────────────────────────────────────────────────
    private Signal activeSignal;
    private int ceQuantity;
    private int peQuantity;

    public BankNiftyDeltaZero(OrderService orderService, MarketDataProvider marketData) {
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
        String ceSymbol = buildSymbol(atmStrike, "CE");
        String peSymbol = buildSymbol(atmStrike, "PE");

        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) return;

        double ceDelta = Math.abs(ceQuote.getDelta());
        double peDelta = Math.abs(peQuote.getDelta());
        if (ceDelta == 0 || peDelta == 0) {
            log.warning("[" + strategyId + "] Zero delta detected; skipping entry");
            return;
        }

        double ratio = peDelta / ceDelta;
        ceQuantity = (int) Math.max(1, Math.round(ratio));
        peQuantity = 1;
        if (ratio < 1.0) {
            ceQuantity = 1;
            peQuantity = (int) Math.max(1, Math.round(1.0 / ratio));
        }

        List<StrategyLeg> legs = new ArrayList<>(2);
        legs.add(StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(atmStrike).quantity(ceQuantity)
                .entryPrice(ceQuote.getLtp()).currentPrice(ceQuote.getLtp())
                .status("OPEN").build());
        legs.add(StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(atmStrike).quantity(peQuantity)
                .entryPrice(peQuote.getLtp()).currentPrice(peQuote.getLtp())
                .status("OPEN").build());

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
        log.info("[" + strategyId + "] DeltaZero entry: CE qty=" + ceQuantity
                + " PE qty=" + peQuantity + " strike=" + atmStrike);
    }

    // ── Exit evaluation (delta rebalancing) ─────────────────────────────

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) return;

        double netDelta = 0;
        double netGamma = 0;

        for (StrategyLeg leg : activeSignal.getLegs()) {
            if (!leg.isOpen()) continue;
            OptionQuote quote = marketData.getOptionQuote(leg.getName());
            if (quote == null) continue;
            double sign = "SELL".equals(leg.getSide()) ? -1.0 : 1.0;
            netDelta += sign * quote.getDelta() * leg.getQuantity();
            netGamma += sign * quote.getGamma() * leg.getQuantity();
        }

        if (Math.abs(netDelta) > DELTA_REBALANCE_THRESHOLD) {
            log.info("[" + strategyId + "] Delta drift: netDelta=" + netDelta
                    + "; rebalancing");
            orderService.placeExitOrders(activeSignal);
            rebalanceEntry(strategyId);
        }

        if (Math.abs(netGamma) > MAX_GAMMA_EXPOSURE) {
            log.warning("[" + strategyId + "] Gamma exposure exceeded: " + netGamma);
        }

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // ── Should exit ─────────────────────────────────────────────────────

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) return false;

        for (StrategyLeg leg : activeSignal.getLegs()) {
            if (!leg.isOpen()) continue;
            OptionQuote quote = marketData.getOptionQuote(leg.getName());
            if (quote == null) continue;
            double slThreshold = leg.getEntryPrice() * (1.0 + LEG_SL_PCT);
            if (quote.getLtp() >= slThreshold) {
                log.info("[" + strategyId + "] Leg SL hit: " + leg.getName()
                        + " current=" + quote.getLtp() + " sl=" + slThreshold);
                return true;
            }
        }
        return false;
    }

    // ── Exit ────────────────────────────────────────────────────────────

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal == null) return;

        orderService.placeExitOrders(activeSignal);
        log.info("[" + strategyId + "] DeltaZero exit: pnl="
                + activeSignal.getTotalUnrealisedPnl());
        activeSignal = null;
    }

    // ── Rebalance helper ────────────────────────────────────────────────

    private void rebalanceEntry(long strategyId) {
        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) return;

        int atmStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);
        String ceSymbol = buildSymbol(atmStrike, "CE");
        String peSymbol = buildSymbol(atmStrike, "PE");
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) return;

        double ceDelta = Math.abs(ceQuote.getDelta());
        double peDelta = Math.abs(peQuote.getDelta());
        double ratio = (ceDelta > 0) ? peDelta / ceDelta : 1.0;
        ceQuantity = (int) Math.max(1, Math.round(ratio));
        peQuantity = 1;

        List<StrategyLeg> legs = new ArrayList<>(2);
        legs.add(StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(atmStrike).quantity(ceQuantity)
                .entryPrice(ceQuote.getLtp()).currentPrice(ceQuote.getLtp())
                .status("OPEN").build());
        legs.add(StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(atmStrike).quantity(peQuantity)
                .entryPrice(peQuote.getLtp()).currentPrice(peQuote.getLtp())
                .status("OPEN").build());

        activeSignal = Signal.builder()
                .strategyId(strategyId).status("LIVE")
                .currentAtm(atmStrike).baseIndexPrice(spot)
                .legs(legs).build();

        orderService.placeEntryOrders(activeSignal);
        log.info("[" + strategyId + "] DeltaZero rebalanced: CE=" + ceQuantity
                + " PE=" + peQuantity);
    }

    private String buildSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + EXPIRY + "-" + strike + optionType;
    }
}
