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
 * BANKNIFTY Rolling Grid Straddle Strategy.
 * <p>
 * Deploys a 10-leg short straddle grid spaced at 100-pt intervals around
 * the ATM strike.  When the underlying moves more than one full strike
 * interval (100 pts), the grid is rolled: all legs are closed and new
 * ATM-centred legs are opened, maintaining a neutral posture across the grid.
 * <p>
 * Key parameters:
 * <ul>
 *   <li>Grid width: 10 legs (5 CE + 5 PE)</li>
 *   <li>Spacing: 100 pts (BANKNIFTY strike interval)</li>
 *   <li>Roll trigger: underlying move > 100 pts from grid centre</li>
 *   <li>Portfolio SL: 10 000 pts aggregate</li>
 * </ul>
 */
public class BankNiftyGridStraddle extends BaseStrategy {

    private static final Logger log = Logger.getLogger(BankNiftyGridStraddle.class.getName());

    // ── Index constants ─────────────────────────────────────────────────
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT    = "NSEFO";
    private static final String EXPIRY     = "currentMonth";
    private static final int    STRIKE_INTERVAL = 100;

    // ── Re-entry and cooldown ───────────────────────────────────────────
    private static final int MAX_RE_ENTRIES   = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // ── Grid parameters ─────────────────────────────────────────────────
    private static final int    LEGS_PER_SIDE    = 5;
    private static final double ROLL_TRIGGER_PTS = 100.0;
    private static final double PORTFOLIO_SL_PTS = 10_000.0;

    // ── Runtime state ───────────────────────────────────────────────────
    private Signal activeSignal;
    private int gridCentreStrike;

    public BankNiftyGridStraddle(OrderService orderService, MarketDataProvider marketData) {
        super(StrategyConfig.builder()
                        .underlying(UNDERLYING)
                        .segment(SEGMENT)
                        .strikeInterval(STRIKE_INTERVAL)
                        .tradingExpiry(EXPIRY)
                        .maxReEntries(MAX_RE_ENTRIES)
                        .exitCooldownSeconds(COOLDOWN_SECONDS)
                        .defaultStopLoss(PORTFOLIO_SL_PTS)
                        .build(),
                orderService, marketData);
    }

    // ── Entry ───────────────────────────────────────────────────────────

    @Override
    protected void onEntry(long strategyId) {
        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) return;

        gridCentreStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);
        List<StrategyLeg> legs = buildGrid(gridCentreStrike);

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(gridCentreStrike)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
        log.info("[" + strategyId + "] GridStraddle entry: centre="
                + gridCentreStrike + " legs=" + legs.size());
    }

    // ── Exit evaluation (rolling) ───────────────────────────────────────

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) return;

        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) return;

        double drift = Math.abs(spot - gridCentreStrike);
        if (drift >= ROLL_TRIGGER_PTS) {
            log.info("[" + strategyId + "] Rolling grid: spot=" + spot
                    + " oldCentre=" + gridCentreStrike);
            orderService.placeExitOrders(activeSignal);

            gridCentreStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);
            List<StrategyLeg> newLegs = buildGrid(gridCentreStrike);

            activeSignal = Signal.builder()
                    .strategyId(strategyId)
                    .status("LIVE")
                    .currentAtm(gridCentreStrike)
                    .baseIndexPrice(spot)
                    .legs(newLegs)
                    .build();

            orderService.placeEntryOrders(activeSignal);
            log.info("[" + strategyId + "] Grid rolled to centre=" + gridCentreStrike);
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
        if (totalPnl <= -PORTFOLIO_SL_PTS) {
            log.info("[" + strategyId + "] Portfolio SL hit: pnl=" + totalPnl);
            return true;
        }
        return false;
    }

    // ── Exit ────────────────────────────────────────────────────────────

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal == null) return;

        orderService.placeExitOrders(activeSignal);
        log.info("[" + strategyId + "] GridStraddle exit");
        activeSignal = null;
    }

    // ── Grid builder ────────────────────────────────────────────────────

    private List<StrategyLeg> buildGrid(int centre) {
        List<StrategyLeg> legs = new ArrayList<>(LEGS_PER_SIDE * 2);

        for (int i = 0; i < LEGS_PER_SIDE; i++) {
            int ceStrike = centre + (i * STRIKE_INTERVAL);
            String ceSymbol = buildSymbol(ceStrike, "CE");
            OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
            double ceLtp = (ceQuote != null) ? ceQuote.getLtp() : 0;
            legs.add(StrategyLeg.builder()
                    .name(ceSymbol).optionType("CE").side("SELL")
                    .strike(ceStrike).quantity(1)
                    .entryPrice(ceLtp).currentPrice(ceLtp)
                    .status("OPEN").build());

            int peStrike = centre - (i * STRIKE_INTERVAL);
            String peSymbol = buildSymbol(peStrike, "PE");
            OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
            double peLtp = (peQuote != null) ? peQuote.getLtp() : 0;
            legs.add(StrategyLeg.builder()
                    .name(peSymbol).optionType("PE").side("SELL")
                    .strike(peStrike).quantity(1)
                    .entryPrice(peLtp).currentPrice(peLtp)
                    .status("OPEN").build());
        }
        return legs;
    }

    // ── PnL computation ─────────────────────────────────────────────────

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
