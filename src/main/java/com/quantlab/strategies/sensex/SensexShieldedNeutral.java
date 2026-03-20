package com.quantlab.strategies.sensex;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

/**
 * SENSEX Shielded Neutral strategy (DN Hedge).
 * <p>
 * Constructs a 4-leg hedged straddle: sells ATM CE + PE and buys
 * protective OTM wings. Hedge strike distance is scaled by DTE,
 * widening as expiry approaches. SENSEX uses wider 200-pt strike
 * intervals and a Rs.5 hedge premium target (reflecting the larger
 * notional value compared to NIFTY/BANKNIFTY).
 * <p>
 * <b>SENSEX-specific parameters:</b>
 * <ul>
 *   <li>Segment: BSEFO</li>
 *   <li>Strike interval: 200 pts</li>
 *   <li>Hedge premium target: Rs.5 (wider strikes than NIFTY Rs.2)</li>
 *   <li>Hedge distance: 3-8 strikes, DTE-scaled</li>
 *   <li>Max loss: 8,000 pts, target profit: 3,000 pts</li>
 * </ul>
 *
 * @see BaseStrategy
 */
public class SensexShieldedNeutral extends BaseStrategy {

    // -- Index & structure --
    private static final String UNDERLYING = "SENSEX";
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int STRIKE_INTERVAL = 200;

    // -- Hedge parameters --
    private static final double HEDGE_PREMIUM_TARGET = 5.0;
    private static final int MIN_HEDGE_DISTANCE_STRIKES = 3;
    private static final int MAX_HEDGE_DISTANCE_STRIKES = 8;
    private static final double DTE_SCALE_FACTOR = 0.5;

    // -- PnL limits --
    private static final double MAX_LOSS_PTS = 8000.0;
    private static final double TARGET_PROFIT_PTS = 3000.0;

    // -- Runtime state --
    private Signal activeSignal;

    public SensexShieldedNeutral(OrderService orderService, MarketDataProvider marketData) {
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
        int hedgeDistance = computeHedgeDistance();
        int hedgeCeStrike = atmStrike + hedgeDistance * STRIKE_INTERVAL;
        int hedgePeStrike = atmStrike - hedgeDistance * STRIKE_INTERVAL;

        String sellCeSym = buildOptionSymbol(atmStrike, "CE");
        String sellPeSym = buildOptionSymbol(atmStrike, "PE");
        String hedgeCeSym = buildOptionSymbol(hedgeCeStrike, "CE");
        String hedgePeSym = buildOptionSymbol(hedgePeStrike, "PE");

        OptionQuote sellCeQ = marketData.getOptionQuote(sellCeSym);
        OptionQuote sellPeQ = marketData.getOptionQuote(sellPeSym);
        OptionQuote hedgeCeQ = marketData.getOptionQuote(hedgeCeSym);
        OptionQuote hedgePeQ = marketData.getOptionQuote(hedgePeSym);

        if (sellCeQ == null || sellPeQ == null || hedgeCeQ == null || hedgePeQ == null) {
            return;
        }

        if (hedgeCeQ.getLtp() > HEDGE_PREMIUM_TARGET * 2
                || hedgePeQ.getLtp() > HEDGE_PREMIUM_TARGET * 2) {
            return;
        }

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .addLeg(buildLeg(sellCeSym, "CE", "SELL", atmStrike, 1, sellCeQ.getLtp()))
                .addLeg(buildLeg(sellPeSym, "PE", "SELL", atmStrike, 1, sellPeQ.getLtp()))
                .addLeg(buildLeg(hedgeCeSym, "CE", "BUY", hedgeCeStrike, 1, hedgeCeQ.getLtp()))
                .addLeg(buildLeg(hedgePeSym, "PE", "BUY", hedgePeStrike, 1, hedgePeQ.getLtp()))
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
        // Hedged position is structurally bounded; no mid-trade adjustment.
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

    private int computeHedgeDistance() {
        // Approximate DTE from synthetic/spot convergence
        double synth = marketData.getSyntheticPrice(UNDERLYING);
        double spot = marketData.getSpotPrice(UNDERLYING);
        int estimatedDte = 7; // default for nextWeek
        if (!Double.isNaN(synth) && !Double.isNaN(spot)) {
            double convergence = Math.abs(synth - spot);
            if (convergence < STRIKE_INTERVAL * 0.3) {
                estimatedDte = 1;
            } else if (convergence < STRIKE_INTERVAL) {
                estimatedDte = 3;
            }
        }

        int scaled = (int) Math.round(MIN_HEDGE_DISTANCE_STRIKES + (estimatedDte * DTE_SCALE_FACTOR));
        return Math.max(MIN_HEDGE_DISTANCE_STRIKES,
                Math.min(scaled, MAX_HEDGE_DISTANCE_STRIKES));
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
