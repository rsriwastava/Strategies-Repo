package com.quantlab.strategies.sensex;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

/**
 * SENSEX Contra Hedge strategy (Ulta Delta Hedge).
 * <p>
 * Inverts the standard hedge assignment: buys ATM options as the core
 * position and sells OTM wings as the hedge. This creates a net-debit
 * spread that profits from large directional moves while capping risk
 * through the sold wings. Hedge assignments are flipped relative to
 * {@link SensexShieldedNeutral}.
 * <p>
 * <b>SENSEX-specific parameters:</b>
 * <ul>
 *   <li>Segment: BSEFO</li>
 *   <li>Strike interval: 200 pts</li>
 *   <li>Wing distance: 4 strikes (800 pts)</li>
 *   <li>Max debit: 500 pts</li>
 *   <li>Max loss: 6,000 pts, target profit: 4,000 pts</li>
 * </ul>
 *
 * @see BaseStrategy
 */
public class SensexContraHedge extends BaseStrategy {

    // -- Index & structure --
    private static final String UNDERLYING = "SENSEX";
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int STRIKE_INTERVAL = 200;

    // -- Inverted hedge parameters --
    private static final int WING_DISTANCE_STRIKES = 4;
    private static final double MAX_DEBIT_PTS = 500.0;

    // -- PnL limits --
    private static final double MAX_LOSS_PTS = 6000.0;
    private static final double TARGET_PROFIT_PTS = 4000.0;

    // -- Runtime state --
    private Signal activeSignal;

    public SensexContraHedge(OrderService orderService, MarketDataProvider marketData) {
        super(buildConfig(), orderService, marketData);
    }

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .underlying(UNDERLYING)
                .exchange(EXCHANGE)
                .segment(SEGMENT)
                .strikeInterval(STRIKE_INTERVAL)
                .tradingExpiry(TRADING_EXPIRY)
                .defaultStopLoss(MAX_LOSS_PTS)
                .build();
    }

    @Override
    protected void onEntry(long strategyId) {
        if (!marketData.isDataFresh(UNDERLYING, 5)) {
            return;
        }

        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) {
            return;
        }

        int atmStrike = marketData.getATM(UNDERLYING, spot, TRADING_EXPIRY);
        int wingCeStrike = atmStrike + WING_DISTANCE_STRIKES * STRIKE_INTERVAL;
        int wingPeStrike = atmStrike - WING_DISTANCE_STRIKES * STRIKE_INTERVAL;

        String buyCeSym = buildOptionSymbol(atmStrike, "CE");
        String buyPeSym = buildOptionSymbol(atmStrike, "PE");
        String sellCeSym = buildOptionSymbol(wingCeStrike, "CE");
        String sellPeSym = buildOptionSymbol(wingPeStrike, "PE");

        OptionQuote bCeQ = marketData.getOptionQuote(buyCeSym);
        OptionQuote bPeQ = marketData.getOptionQuote(buyPeSym);
        OptionQuote sCeQ = marketData.getOptionQuote(sellCeSym);
        OptionQuote sPeQ = marketData.getOptionQuote(sellPeSym);

        if (bCeQ == null || bPeQ == null || sCeQ == null || sPeQ == null) {
            return;
        }

        double netDebit = (bCeQ.getLtp() + bPeQ.getLtp()) - (sCeQ.getLtp() + sPeQ.getLtp());
        if (netDebit > MAX_DEBIT_PTS) {
            return;
        }

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .addLeg(buildLeg(buyCeSym, "CE", "BUY", atmStrike, 1, bCeQ.getLtp()))
                .addLeg(buildLeg(buyPeSym, "PE", "BUY", atmStrike, 1, bPeQ.getLtp()))
                .addLeg(buildLeg(sellCeSym, "CE", "SELL", wingCeStrike, 1, sCeQ.getLtp()))
                .addLeg(buildLeg(sellPeSym, "PE", "SELL", wingPeStrike, 1, sPeQ.getLtp()))
                .build();

        orderService.placeEntryOrders(activeSignal);
    }

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) {
            return false;
        }
        double pnl = computePnl();
        return pnl <= -MAX_LOSS_PTS || pnl >= TARGET_PROFIT_PTS;
    }

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            activeSignal = null;
        }
    }

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) {
            return;
        }
        // Inverted spread is structurally bounded by sold wings. No mid-trade adjustment.
        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // -- Private helpers --

    private String buildOptionSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + TRADING_EXPIRY + "-" + strike + optionType;
    }

    private StrategyLeg buildLeg(String symbol, String optionType, String side,
                                  int strike, int qty, double price) {
        return StrategyLeg.builder()
                .name(symbol).optionType(optionType).side(side)
                .strike(strike).quantity(qty)
                .entryPrice(price).currentPrice(price)
                .status("OPEN").build();
    }

    private double computePnl() {
        if (activeSignal == null) {
            return 0.0;
        }
        double pnl = 0.0;
        for (StrategyLeg leg : activeSignal.getLegs()) {
            OptionQuote q = marketData.getOptionQuote(leg.getName());
            if (q != null) {
                double diff = q.getLtp() - leg.getEntryPrice();
                pnl += "SELL".equals(leg.getSide()) ? -diff : diff;
            }
        }
        return pnl;
    }
}
