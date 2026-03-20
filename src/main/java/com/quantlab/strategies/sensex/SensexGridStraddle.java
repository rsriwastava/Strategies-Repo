package com.quantlab.strategies.sensex;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SENSEX Rolling Grid Straddle strategy.
 * <p>
 * Deploys a 10-leg straddle grid with 200-pt spacing across SENSEX
 * strikes. When the underlying moves more than one full strike interval
 * (200 pts) from the grid centre, the farthest out-of-range legs are
 * rolled to maintain ATM-centred exposure. Fixed stop-loss at 10,000 pts
 * total portfolio loss.
 * <p>
 * <b>SENSEX-specific parameters:</b>
 * <ul>
 *   <li>Segment: BSEFO</li>
 *   <li>Strike interval: 200 pts</li>
 *   <li>Grid spacing: 200 pts between legs</li>
 *   <li>Portfolio SL: 10,000 pts</li>
 *   <li>Max re-entries: 5, cooldown: 60s</li>
 * </ul>
 *
 * @see BaseStrategy
 */
public class SensexGridStraddle extends BaseStrategy {

    // -- Index & structure --
    private static final String UNDERLYING = "SENSEX";
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int STRIKE_INTERVAL = 200;

    // -- Grid parameters --
    private static final int TOTAL_LEGS = 10;
    private static final int HALF_LEGS = TOTAL_LEGS / 2;
    private static final double ROLL_TRIGGER_PTS = 200.0;
    private static final double PORTFOLIO_SL_PTS = 10000.0;

    // -- Re-entry --
    private static final int MAX_RE_ENTRIES = 5;
    private static final int COOLDOWN_SECONDS = 60;

    // -- Runtime state --
    private int gridCentreStrike;
    private Signal activeSignal;
    private final double[] ceEntryPrices = new double[TOTAL_LEGS];
    private final double[] peEntryPrices = new double[TOTAL_LEGS];
    private final int[] gridStrikes = new int[TOTAL_LEGS];
    private Instant lastEntryTime = Instant.EPOCH;
    private int reEntryCount;

    public SensexGridStraddle(OrderService orderService, MarketDataProvider marketData) {
        super(buildConfig(), orderService, marketData);
    }

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .underlying(UNDERLYING)
                .exchange(EXCHANGE)
                .segment(SEGMENT)
                .strikeInterval(STRIKE_INTERVAL)
                .tradingExpiry(TRADING_EXPIRY)
                .maxReEntries(MAX_RE_ENTRIES)
                .exitCooldownSeconds(COOLDOWN_SECONDS)
                .defaultStopLoss(PORTFOLIO_SL_PTS)
                .build();
    }

    @Override
    protected void onEntry(long strategyId) {
        if (reEntryCount >= MAX_RE_ENTRIES) {
            return;
        }
        if (Duration.between(lastEntryTime, Instant.now()).getSeconds() < COOLDOWN_SECONDS) {
            return;
        }
        if (!marketData.isDataFresh(UNDERLYING, 5)) {
            return;
        }

        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) {
            return;
        }

        gridCentreStrike = marketData.getATM(UNDERLYING, spot, TRADING_EXPIRY);
        List<StrategyLeg> legs = new ArrayList<>();

        for (int i = 0; i < TOTAL_LEGS; i++) {
            int offset = (i - HALF_LEGS + 1) * STRIKE_INTERVAL;
            int strike = gridCentreStrike + offset;
            gridStrikes[i] = strike;

            String ceSymbol = buildOptionSymbol(strike, "CE");
            String peSymbol = buildOptionSymbol(strike, "PE");
            OptionQuote ceQ = marketData.getOptionQuote(ceSymbol);
            OptionQuote peQ = marketData.getOptionQuote(peSymbol);

            if (ceQ == null || peQ == null) {
                return;
            }

            ceEntryPrices[i] = ceQ.getLtp();
            peEntryPrices[i] = peQ.getLtp();

            legs.add(StrategyLeg.builder()
                    .name(ceSymbol).optionType("CE").side("SELL")
                    .strike(strike).quantity(1)
                    .entryPrice(ceQ.getLtp()).currentPrice(ceQ.getLtp())
                    .status("OPEN").build());

            legs.add(StrategyLeg.builder()
                    .name(peSymbol).optionType("PE").side("SELL")
                    .strike(strike).quantity(1)
                    .entryPrice(peQ.getLtp()).currentPrice(peQ.getLtp())
                    .status("OPEN").build());
        }

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(gridCentreStrike)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
        lastEntryTime = Instant.now();
        reEntryCount++;
    }

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) {
            return false;
        }
        return computePortfolioPnl() <= -PORTFOLIO_SL_PTS;
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
        if (Double.isNaN(spot)) {
            return;
        }

        double drift = Math.abs(spot - gridCentreStrike);
        if (drift >= ROLL_TRIGGER_PTS) {
            int newCentre = marketData.getATM(UNDERLYING, spot, TRADING_EXPIRY);
            rollGrid(strategyId, newCentre);
        }

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // -- Private helpers --

    private String buildOptionSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + TRADING_EXPIRY + "-" + strike + optionType;
    }

    private void rollGrid(long strategyId, int newCentre) {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
        }

        gridCentreStrike = newCentre;
        double spot = marketData.getSpotPrice(UNDERLYING);
        List<StrategyLeg> legs = new ArrayList<>();

        for (int i = 0; i < TOTAL_LEGS; i++) {
            int offset = (i - HALF_LEGS + 1) * STRIKE_INTERVAL;
            int strike = newCentre + offset;
            gridStrikes[i] = strike;

            String ceSymbol = buildOptionSymbol(strike, "CE");
            String peSymbol = buildOptionSymbol(strike, "PE");
            OptionQuote ceQ = marketData.getOptionQuote(ceSymbol);
            OptionQuote peQ = marketData.getOptionQuote(peSymbol);

            ceEntryPrices[i] = ceQ != null ? ceQ.getLtp() : 0.0;
            peEntryPrices[i] = peQ != null ? peQ.getLtp() : 0.0;

            legs.add(StrategyLeg.builder()
                    .name(ceSymbol).optionType("CE").side("SELL")
                    .strike(strike).quantity(1)
                    .entryPrice(ceEntryPrices[i]).currentPrice(ceEntryPrices[i])
                    .status("OPEN").build());

            legs.add(StrategyLeg.builder()
                    .name(peSymbol).optionType("PE").side("SELL")
                    .strike(strike).quantity(1)
                    .entryPrice(peEntryPrices[i]).currentPrice(peEntryPrices[i])
                    .status("OPEN").build());
        }

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(newCentre)
                .baseIndexPrice(!Double.isNaN(spot) ? spot : 0.0)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
    }

    private double computePortfolioPnl() {
        if (activeSignal == null) {
            return 0.0;
        }
        double pnl = 0.0;
        for (StrategyLeg leg : activeSignal.getLegs()) {
            OptionQuote q = marketData.getOptionQuote(leg.getName());
            if (q != null) {
                // Sold legs: entry - current = profit
                pnl += leg.getEntryPrice() - q.getLtp();
            }
        }
        return pnl;
    }
}
