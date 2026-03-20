package com.quantlab.strategies.sensex;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

import java.util.ArrayList;
import java.util.List;

/**
 * SENSEX Pair Strike strategy (Jodi).
 * <p>
 * Sells a paired straddle on two adjacent ATM strikes separated by
 * one strike interval (200 pts). When the underlying drifts beyond
 * a 200-pt adjustment threshold from the pair centre, the entire
 * position is exited and re-entered at the new ATM level, maintaining
 * continuous theta exposure with minimal directional bias.
 * <p>
 * <b>SENSEX-specific parameters:</b>
 * <ul>
 *   <li>Segment: BSEFO</li>
 *   <li>Strike interval: 200 pts</li>
 *   <li>Adjustment trigger: 200 pts from pair centre</li>
 *   <li>Max loss: 6,000 pts, target profit: 3,000 pts</li>
 * </ul>
 *
 * @see BaseStrategy
 */
public class SensexPairStrike extends BaseStrategy {

    // -- Index & structure --
    private static final String UNDERLYING = "SENSEX";
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int STRIKE_INTERVAL = 200;

    // -- Pair parameters --
    private static final double ADJUSTMENT_TRIGGER_PTS = 200.0;

    // -- PnL limits --
    private static final double MAX_LOSS_PTS = 6000.0;
    private static final double TARGET_PROFIT_PTS = 3000.0;

    // -- Runtime state --
    private Signal activeSignal;
    private int pairLowerStrike;
    private int pairUpperStrike;
    private double pairCentre;

    public SensexPairStrike(OrderService orderService, MarketDataProvider marketData) {
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

        pairLowerStrike = marketData.getATM(UNDERLYING, spot, TRADING_EXPIRY);
        pairUpperStrike = pairLowerStrike + STRIKE_INTERVAL;
        pairCentre = (pairLowerStrike + pairUpperStrike) / 2.0;

        activeSignal = buildPairSignal(strategyId, spot, pairLowerStrike, pairUpperStrike);
        if (activeSignal == null) {
            return;
        }

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

        double spot = marketData.getSpotPrice(UNDERLYING);
        if (!Double.isNaN(spot) && Math.abs(spot - pairCentre) >= ADJUSTMENT_TRIGGER_PTS) {
            adjustPair(strategyId, spot);
        }

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // -- Private helpers --

    private String buildOptionSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + TRADING_EXPIRY + "-" + strike + optionType;
    }

    private Signal buildPairSignal(long strategyId, double spot, int lower, int upper) {
        List<StrategyLeg> legs = new ArrayList<>();

        String[] symbols = {
                buildOptionSymbol(lower, "CE"), buildOptionSymbol(lower, "PE"),
                buildOptionSymbol(upper, "CE"), buildOptionSymbol(upper, "PE")
        };
        String[] optTypes = {"CE", "PE", "CE", "PE"};
        int[] strikes = {lower, lower, upper, upper};

        for (int i = 0; i < symbols.length; i++) {
            OptionQuote q = marketData.getOptionQuote(symbols[i]);
            if (q == null) {
                return null;
            }
            legs.add(StrategyLeg.builder()
                    .name(symbols[i]).optionType(optTypes[i]).side("SELL")
                    .strike(strikes[i]).quantity(1)
                    .entryPrice(q.getLtp()).currentPrice(q.getLtp())
                    .status("OPEN").build());
        }

        return Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(lower)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();
    }

    private void adjustPair(long strategyId, double spot) {
        orderService.placeExitOrders(activeSignal);

        pairLowerStrike = marketData.getATM(UNDERLYING, spot, TRADING_EXPIRY);
        pairUpperStrike = pairLowerStrike + STRIKE_INTERVAL;
        pairCentre = (pairLowerStrike + pairUpperStrike) / 2.0;

        activeSignal = buildPairSignal(strategyId, spot, pairLowerStrike, pairUpperStrike);
        if (activeSignal != null) {
            orderService.placeEntryOrders(activeSignal);
        }
    }

    private double computePnl() {
        if (activeSignal == null) {
            return 0.0;
        }
        double pnl = 0.0;
        for (StrategyLeg leg : activeSignal.getLegs()) {
            OptionQuote q = marketData.getOptionQuote(leg.getName());
            if (q != null) {
                pnl += (leg.getEntryPrice() - q.getLtp()) * leg.getQuantity();
            }
        }
        return pnl;
    }
}
