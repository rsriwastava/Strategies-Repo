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

/**
 * SENSEX Phoenix Core strategy.
 * <p>
 * Sells an ATM straddle and manages it with fixed stop-loss and
 * profit targets. On exit (whether by SL or target), the strategy
 * automatically re-enters at the new ATM strike after a brief
 * cooldown -- hence "Phoenix", rising from the ashes of each
 * stopped-out position.
 * <p>
 * <b>SENSEX-specific parameters:</b>
 * <ul>
 *   <li>Segment: BSEFO</li>
 *   <li>Strike interval: 200 pts</li>
 *   <li>Stop-loss: 3,000 pts</li>
 *   <li>Target profit: 1,500 pts</li>
 *   <li>Max re-entries: 10, cooldown: 60s</li>
 * </ul>
 *
 * @see BaseStrategy
 */
public class SensexPhoenixCore extends BaseStrategy {

    // -- Index & structure --
    private static final String UNDERLYING = "SENSEX";
    private static final String SEGMENT = "BSEFO";
    private static final String EXCHANGE = "BSE";
    private static final String TRADING_EXPIRY = "nextWeek";
    private static final int STRIKE_INTERVAL = 200;

    // -- PnL parameters --
    private static final double STOP_LOSS_PTS = 3000.0;
    private static final double TARGET_PROFIT_PTS = 1500.0;

    // -- Re-entry --
    private static final int MAX_RE_ENTRIES = 10;
    private static final int COOLDOWN_SECONDS = 60;

    // -- Runtime state --
    private Signal activeSignal;
    private Instant lastEntryTime = Instant.EPOCH;
    private int reEntryCount;

    public SensexPhoenixCore(OrderService orderService, MarketDataProvider marketData) {
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
                .defaultStopLoss(STOP_LOSS_PTS)
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

        int atmStrike = marketData.getATM(UNDERLYING, spot, TRADING_EXPIRY);
        String ceSymbol = buildOptionSymbol(atmStrike, "CE");
        String peSymbol = buildOptionSymbol(atmStrike, "PE");

        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) {
            return;
        }

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .addLeg(buildLeg(ceSymbol, "CE", "SELL", atmStrike, 1, ceQuote.getLtp()))
                .addLeg(buildLeg(peSymbol, "PE", "SELL", atmStrike, 1, peQuote.getLtp()))
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
        double pnl = computePnl();
        return pnl <= -STOP_LOSS_PTS || pnl >= TARGET_PROFIT_PTS;
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
        // Phoenix relies on binary SL/target exits with automatic re-entry.
        // No trailing stop or mid-position adjustment.
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
                pnl += (leg.getEntryPrice() - q.getLtp()) * leg.getQuantity();
            }
        }
        return pnl;
    }
}
