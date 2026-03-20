package com.quantlab.strategies.sensex;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

/**
 * SENSEX Delta Neutral strategy (DeltaZero).
 * <p>
 * Constructs a 2-leg ratio straddle at ATM, adjusting CE:PE lot ratios
 * to achieve near-zero portfolio delta. The ratio is recalculated on
 * each evaluation tick, and legs are rebalanced when net delta drifts
 * beyond the configured threshold.
 * <p>
 * <b>SENSEX-specific parameters:</b>
 * <ul>
 *   <li>Segment: BSEFO</li>
 *   <li>Strike interval: 200 pts</li>
 *   <li>Expiry: nextWeek</li>
 *   <li>Max net delta tolerance: 0.10</li>
 *   <li>Rebalance trigger: 0.15</li>
 *   <li>Max loss: 5,000 pts</li>
 * </ul>
 *
 * @see BaseStrategy
 */
public class SensexDeltaZero extends BaseStrategy {

    // -- Index & structure --
    private static final String UNDERLYING = "SENSEX";
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int STRIKE_INTERVAL = 200;

    // -- Delta thresholds --
    private static final double MAX_NET_DELTA = 0.10;
    private static final double REBALANCE_DELTA_TRIGGER = 0.15;

    // -- Position sizing --
    private static final int BASE_LOTS = 1;
    private static final int MAX_RATIO = 5;

    // -- PnL stop --
    private static final double MAX_LOSS_PTS = 5000.0;

    // -- Runtime state --
    private Signal activeSignal;
    private int ceLots;
    private int peLots;
    private int activeStrike;

    public SensexDeltaZero(OrderService orderService, MarketDataProvider marketData) {
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

        activeStrike = marketData.getATM(UNDERLYING, spot, TRADING_EXPIRY);
        String ceSymbol = buildOptionSymbol(activeStrike, "CE");
        String peSymbol = buildOptionSymbol(activeStrike, "PE");

        double ceDelta = marketData.getDelta(ceSymbol);
        double peDelta = marketData.getDelta(peSymbol);

        if (Double.isNaN(ceDelta) || Double.isNaN(peDelta)
                || Math.abs(ceDelta) <= 0 || Math.abs(peDelta) <= 0) {
            return;
        }

        int[] ratio = computeRatio(Math.abs(ceDelta), Math.abs(peDelta));
        ceLots = ratio[0];
        peLots = ratio[1];

        OptionQuote ceQ = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQ = marketData.getOptionQuote(peSymbol);
        if (ceQ == null || peQ == null) {
            return;
        }

        StrategyLeg ceLeg = StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(activeStrike).quantity(ceLots)
                .entryPrice(ceQ.getLtp()).currentPrice(ceQ.getLtp())
                .status("OPEN").build();

        StrategyLeg peLeg = StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(activeStrike).quantity(peLots)
                .entryPrice(peQ.getLtp()).currentPrice(peQ.getLtp())
                .status("OPEN").build();

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(activeStrike)
                .baseIndexPrice(spot)
                .addLeg(ceLeg).addLeg(peLeg)
                .build();

        orderService.placeEntryOrders(activeSignal);
    }

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) {
            return false;
        }
        return computePnl() <= -MAX_LOSS_PTS;
    }

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            activeSignal = null;
        }
        ceLots = 0;
        peLots = 0;
    }

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) {
            return;
        }

        String ceSymbol = buildOptionSymbol(activeStrike, "CE");
        String peSymbol = buildOptionSymbol(activeStrike, "PE");

        double ceDelta = marketData.getDelta(ceSymbol);
        double peDelta = marketData.getDelta(peSymbol);

        if (!Double.isNaN(ceDelta) && !Double.isNaN(peDelta)) {
            double netDelta = (ceDelta * ceLots) + (peDelta * peLots);
            if (Math.abs(netDelta) > REBALANCE_DELTA_TRIGGER) {
                rebalance(strategyId, ceSymbol, peSymbol);
            }
        }

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // -- Private helpers --

    private String buildOptionSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + TRADING_EXPIRY + "-" + strike + optionType;
    }

    private int[] computeRatio(double ceDelta, double peDelta) {
        double rawRatio = peDelta / ceDelta;
        int ceR = BASE_LOTS;
        int peR = (int) Math.round(rawRatio * BASE_LOTS);
        peR = Math.max(BASE_LOTS, Math.min(peR, MAX_RATIO));
        return new int[]{ceR, peR};
    }

    private void rebalance(long strategyId, String ceSymbol, String peSymbol) {
        double ceDelta = Math.abs(marketData.getDelta(ceSymbol));
        double peDelta = Math.abs(marketData.getDelta(peSymbol));
        int[] newRatio = computeRatio(ceDelta, peDelta);

        // Exit current signal and re-enter with new ratios
        orderService.placeExitOrders(activeSignal);

        ceLots = newRatio[0];
        peLots = newRatio[1];

        OptionQuote ceQ = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQ = marketData.getOptionQuote(peSymbol);

        StrategyLeg ceLeg = StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(activeStrike).quantity(ceLots)
                .entryPrice(ceQ != null ? ceQ.getLtp() : 0).currentPrice(ceQ != null ? ceQ.getLtp() : 0)
                .status("OPEN").build();

        StrategyLeg peLeg = StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(activeStrike).quantity(peLots)
                .entryPrice(peQ != null ? peQ.getLtp() : 0).currentPrice(peQ != null ? peQ.getLtp() : 0)
                .status("OPEN").build();

        double spot = marketData.getSpotPrice(UNDERLYING);
        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(activeStrike)
                .baseIndexPrice(!Double.isNaN(spot) ? spot : 0.0)
                .addLeg(ceLeg).addLeg(peLeg)
                .build();

        orderService.placeEntryOrders(activeSignal);
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
