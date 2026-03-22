package com.quantlab.strategies.popular.nifty;

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
 * <b>NiftyCallAscent</b> &mdash; Call Ladder (Short Call Ladder).
 *
 * <p>Leg structure:
 * <ul>
 *   <li>Buy 1 ATM CE</li>
 *   <li>Sell 1 OTM CE at ATM + 2 &times; strikeInterval (ATM+100)</li>
 *   <li>Sell 1 far OTM CE at ATM + 4 &times; strikeInterval (ATM+200)</li>
 * </ul>
 *
 * <p>A strategy that results in a small net debit or credit, profiting when
 * the underlying settles near the middle strike at expiry. The two sold calls
 * at higher strikes collect premium to offset the ATM purchase.
 *
 * <p>Max profit = at middle strike at expiry. Max loss = unlimited above the
 * highest strike if both short legs expire ITM.
 *
 * <p>Entry: expecting limited upside, elevated IV (selling expensive far OTM).
 *
 * <p>Exit conditions:
 * <ol>
 *   <li>Target when underlying is near the middle strike</li>
 *   <li>Stop-loss if underlying breaks above highest strike by 1&times;interval</li>
 *   <li>Time exit at 14:30 IST on expiry day</li>
 * </ol>
 *
 * @author QuantLab
 */
public class NiftyCallAscent extends BaseStrategy {

    private static final String STRATEGY_NAME = "NiftyCallAscent";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final String UNDERLYING = "NIFTY";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";

    private static final int MIDDLE_OFFSET = 2;
    private static final int FAR_OFFSET = 4;
    private static final int BREAKEVEN_BUFFER = 1;
    private static final double TARGET_PROXIMITY_PCT = 0.005;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final int LOT_QTY = 1;

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private int middleStrike;
    private int farStrike;
    private int breakevenUpper;
    private double entryNetCost;
    private boolean positionOpen;

    /**
     * Constructs a new NiftyCallAscent strategy.
     *
     * @param orderService       broker-agnostic order service
     * @param marketDataProvider broker-agnostic market data provider
     */
    public NiftyCallAscent(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * <p>Buys 1 ATM CE, sells 1 OTM CE (ATM+100), and sells 1 far OTM CE
     * (ATM+200) to construct the call ladder.
     */
    @Override
    public void onEntry() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(ENTRY_TIME)) {
            logger.info("[{}] Entry blocked — before {} IST", STRATEGY_NAME, ENTRY_TIME);
            return;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrikeInterval(spot);
        middleStrike = atmStrike + MIDDLE_OFFSET * STRIKE_INTERVAL;
        farStrike = atmStrike + FAR_OFFSET * STRIKE_INTERVAL;
        breakevenUpper = farStrike + BREAKEVEN_BUFFER * STRIKE_INTERVAL;

        LegOrder longATM = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(atmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        LegOrder shortMiddle = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(middleStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.SELL)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        LegOrder shortFar = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(farStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.SELL)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        double premPaid = orderService.placeLeg(longATM);
        double premMiddle = orderService.placeLeg(shortMiddle);
        double premFar = orderService.placeLeg(shortFar);

        entryNetCost = premPaid - premMiddle - premFar;
        positionOpen = true;

        logger.info("{}: Entry placed. ATM={}, middle={}, far={}, netCost={}",
                STRATEGY_NAME, atmStrike, middleStrike, farStrike, entryNetCost);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks target (underlying near middle strike), stop-loss (underlying
     * breaks above highest strike plus buffer), and time exit.
     *
     * @return {@code true} if any exit condition is met
     */
    @Override
    public boolean shouldExit() {
        if (!positionOpen) {
            return false;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);

        // Target: underlying near the middle strike (within 0.5%)
        if (Math.abs(spot - middleStrike) / middleStrike <= TARGET_PROXIMITY_PCT) {
            PositionSnapshot snapshot = marketDataProvider.getPositionSnapshot(STRATEGY_NAME);
            double pnl = snapshot.getUnrealisedPnL();
            if (pnl > 0) {
                logger.info("{}: Target reached. Spot={} near middle={}", STRATEGY_NAME, spot, middleStrike);
                return true;
            }
        }

        // Stop-loss: underlying breaks above highest strike + buffer
        if (spot >= breakevenUpper) {
            logger.info("{}: SL triggered. Spot={} >= breakeven={}", STRATEGY_NAME, spot, breakevenUpper);
            return true;
        }

        // Time exit
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
     * <p>Squares off all three legs of the call ladder.
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
