package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.model.LegOrder;
import com.quantlab.strategies.core.model.OptionType;
import com.quantlab.strategies.core.model.OrderSide;
import com.quantlab.strategies.core.model.PositionSnapshot;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * <b>NiftyBullRiser</b> &mdash; Bull Call Spread.
 *
 * <p>Leg structure:
 * <ul>
 *   <li>Buy 1 ATM CE</li>
 *   <li>Sell 1 OTM CE at ATM + 3 &times; strikeInterval (ATM+150)</li>
 * </ul>
 *
 * <p>A net-debit strategy that profits from a moderate upside move. The sold
 * OTM call finances part of the ATM purchase, capping both profit and loss.
 *
 * <p>Max profit = strike width &minus; net debit. Max loss = net debit paid.
 *
 * <p>Entry: moderately bullish outlook, IV neutral to high (selling expensive
 * OTM premium).
 *
 * <p>Exit conditions:
 * <ol>
 *   <li>Target 80% of max profit</li>
 *   <li>Stop-loss at 50% of net debit</li>
 *   <li>Time exit at 14:00 IST on expiry day</li>
 * </ol>
 *
 * @author QuantLab
 */
public class NiftyBullRiser extends BaseStrategy {

    private static final String STRATEGY_NAME = "NiftyBullRiser";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final String UNDERLYING = "NIFTY";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";

    private static final int BULL_SPREAD_WIDTH = 3;
    private static final double TARGET_PCT = 0.8;
    private static final double SL_PCT = 0.5;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 0);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final int LOT_QTY = 1;

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double netDebit;
    private double maxProfit;
    private boolean positionOpen;

    /**
     * Constructs a new NiftyBullRiser strategy.
     *
     * @param orderService       broker-agnostic order service
     * @param marketDataProvider broker-agnostic market data provider
     */
    public NiftyBullRiser(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
        this.positionOpen = false;
    }

    private static StrategyConfig buildConfig() {
        return new StrategyConfig.Builder()
                .name(STRATEGY_NAME)
                .underlying(UNDERLYING)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .strikeInterval(STRIKE_INTERVAL)
                .tradingExpiry(TRADING_EXPIRY)
                .build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Buys 1 ATM CE and sells 1 OTM CE (ATM+150) to construct the bull
     * call spread.
     */
    @Override
    public void onEntry() {
        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrikeInterval(spot);
        int otmStrike = atmStrike + BULL_SPREAD_WIDTH * STRIKE_INTERVAL;

        LegOrder longLeg = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(atmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        LegOrder shortLeg = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(otmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.SELL)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        double premiumPaid = orderService.placeLeg(longLeg);
        double premiumReceived = orderService.placeLeg(shortLeg);

        netDebit = premiumPaid - premiumReceived;
        int strikeWidth = otmStrike - atmStrike;
        maxProfit = strikeWidth - netDebit;
        positionOpen = true;

        logger.info("{}: Entry placed. ATM={}, OTM={}, netDebit={}, maxProfit={}",
                STRATEGY_NAME, atmStrike, otmStrike, netDebit, maxProfit);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks target (80% of max profit), stop-loss (50% of debit), and
     * time-based exit.
     *
     * @return {@code true} if any exit condition is met
     */
    @Override
    public boolean shouldExit() {
        if (!positionOpen) {
            return false;
        }

        PositionSnapshot snapshot = marketDataProvider.getPositionSnapshot(STRATEGY_NAME);
        double currentPnL = snapshot.getUnrealisedPnL();

        if (currentPnL >= maxProfit * TARGET_PCT) {
            logger.info("{}: Target profit reached. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        if (currentPnL <= -(netDebit * SL_PCT)) {
            logger.info("{}: Stop-loss hit. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        ZonedDateTime now = ZonedDateTime.now(IST);
        if (marketDataProvider.isExpiryDay(UNDERLYING, TRADING_EXPIRY)
                && now.toLocalTime().isAfter(TIME_EXIT)) {
            logger.info("{}: Time exit triggered at {}", STRATEGY_NAME, now.toLocalTime());
            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Squares off all open legs of the bull call spread.
     */
    @Override
    public void onExit() {
        if (!positionOpen) {
            return;
        }
        orderService.squareOffAll(STRATEGY_NAME);
        positionOpen = false;
        logger.info("{}: All positions squared off.", STRATEGY_NAME);
    }

    private int roundToStrikeInterval(double price) {
        return (int) (Math.round(price / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
