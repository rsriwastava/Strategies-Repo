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
 * <b>NiftyPutDescent</b> &mdash; Put Ladder (Short Put Ladder).
 *
 * <p>Leg structure:
 * <ul>
 *   <li>Buy 1 ATM PE</li>
 *   <li>Sell 1 OTM PE at ATM &minus; 2 &times; strikeInterval (ATM&minus;100)</li>
 *   <li>Sell 1 far OTM PE at ATM &minus; 4 &times; strikeInterval (ATM&minus;200)</li>
 * </ul>
 *
 * <p>A strategy that results in a small net debit or credit, profiting when
 * the underlying settles near the middle strike at expiry. The two sold puts
 * at lower strikes collect premium to offset the ATM purchase.
 *
 * <p>Max profit = at middle strike at expiry. Max loss = large below the
 * lowest strike.
 *
 * <p>Entry: expecting limited downside.
 *
 * <p>Exit conditions:
 * <ol>
 *   <li>Target when underlying is near the middle strike</li>
 *   <li>Stop-loss if underlying breaks below lowest strike by 1&times;interval</li>
 *   <li>Time exit at 14:30 IST on expiry day</li>
 * </ol>
 *
 * @author QuantLab
 */
public class NiftyPutDescent extends BaseStrategy {

    private static final String STRATEGY_NAME = "NiftyPutDescent";
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
    private int breakevenLower;
    private double entryNetCost;
    private boolean positionOpen;

    /**
     * Constructs a new NiftyPutDescent strategy.
     *
     * @param orderService       broker-agnostic order service
     * @param marketDataProvider broker-agnostic market data provider
     */
    public NiftyPutDescent(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * <p>Buys 1 ATM PE, sells 1 OTM PE (ATM&minus;100), and sells 1 far OTM
     * PE (ATM&minus;200) to construct the put ladder.
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
        middleStrike = atmStrike - MIDDLE_OFFSET * STRIKE_INTERVAL;
        farStrike = atmStrike - FAR_OFFSET * STRIKE_INTERVAL;
        breakevenLower = farStrike - BREAKEVEN_BUFFER * STRIKE_INTERVAL;

        LegOrder longATM = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(atmStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.BUY)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        LegOrder shortMiddle = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(middleStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.SELL)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        LegOrder shortFar = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(farStrike)
                .optionType(OptionType.PE)
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
     * breaks below lowest strike minus buffer), and time exit.
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

        // Stop-loss: underlying breaks below lowest strike - buffer
        if (spot <= breakevenLower) {
            logger.info("{}: SL triggered. Spot={} <= breakeven={}", STRATEGY_NAME, spot, breakevenLower);
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
     * <p>Squares off all three legs of the put ladder.
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
