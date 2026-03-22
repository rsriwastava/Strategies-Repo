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
 * <b>NiftyIronWing</b> &mdash; Iron Butterfly.
 *
 * <p>Leg structure:
 * <ul>
 *   <li>Sell 1 ATM CE</li>
 *   <li>Sell 1 ATM PE</li>
 *   <li>Buy 1 OTM CE at ATM + 2 &times; strikeInterval (ATM+100)</li>
 *   <li>Buy 1 OTM PE at ATM &minus; 2 &times; strikeInterval (ATM&minus;100)</li>
 * </ul>
 *
 * <p>A net-credit strategy that profits from low volatility and range-bound
 * markets. The sold ATM straddle generates premium while the bought wings
 * limit risk.
 *
 * <p>Max profit = net premium received. Max loss = wing width &minus; premium.
 *
 * <p>Entry: VIX &gt; 15, expecting range-bound or volatility crush.
 *
 * <p>Exit conditions:
 * <ol>
 *   <li>Target 50% of max profit (theta decay)</li>
 *   <li>Stop-loss when loss equals premium received</li>
 *   <li>Time exit at 14:30 IST on expiry day</li>
 *   <li>Underlying moves beyond a wing strike</li>
 * </ol>
 *
 * @author QuantLab
 */
public class NiftyIronWing extends BaseStrategy {

    private static final String STRATEGY_NAME = "NiftyIronWing";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final String UNDERLYING = "NIFTY";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";

    private static final int WING_WIDTH = 2;
    private static final double TARGET_PCT = 0.5;
    private static final double VIX_ENTRY_THRESHOLD = 15.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final int LOT_QTY = 1;

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double netPremium;
    private int upperWingStrike;
    private int lowerWingStrike;
    private boolean positionOpen;

    /**
     * Constructs a new NiftyIronWing strategy.
     *
     * @param orderService       broker-agnostic order service
     * @param marketDataProvider broker-agnostic market data provider
     */
    public NiftyIronWing(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * <p>Sells ATM CE + ATM PE (short straddle), buys OTM CE + OTM PE (wings).
     * Entry requires VIX above the threshold.
     */
    @Override
    public void onEntry() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(ENTRY_TIME)) {
            logger.info("[{}] Entry blocked — before {} IST", STRATEGY_NAME, ENTRY_TIME);
            return;
        }

        double vix = marketDataProvider.getVIX();
        if (vix <= VIX_ENTRY_THRESHOLD) {
            logger.info("{}: VIX {} <= threshold {}. Skipping entry.", STRATEGY_NAME, vix, VIX_ENTRY_THRESHOLD);
            return;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrikeInterval(spot);
        upperWingStrike = atmStrike + WING_WIDTH * STRIKE_INTERVAL;
        lowerWingStrike = atmStrike - WING_WIDTH * STRIKE_INTERVAL;

        LegOrder shortCE = LegOrder.builder()
                .underlying(UNDERLYING).strike(atmStrike).optionType(OptionType.CE)
                .side(OrderSide.SELL).quantity(LOT_QTY).expiry(TRADING_EXPIRY)
                .segment(SEGMENT).exchange(EXCHANGE).build();

        LegOrder shortPE = LegOrder.builder()
                .underlying(UNDERLYING).strike(atmStrike).optionType(OptionType.PE)
                .side(OrderSide.SELL).quantity(LOT_QTY).expiry(TRADING_EXPIRY)
                .segment(SEGMENT).exchange(EXCHANGE).build();

        LegOrder longCE = LegOrder.builder()
                .underlying(UNDERLYING).strike(upperWingStrike).optionType(OptionType.CE)
                .side(OrderSide.BUY).quantity(LOT_QTY).expiry(TRADING_EXPIRY)
                .segment(SEGMENT).exchange(EXCHANGE).build();

        LegOrder longPE = LegOrder.builder()
                .underlying(UNDERLYING).strike(lowerWingStrike).optionType(OptionType.PE)
                .side(OrderSide.BUY).quantity(LOT_QTY).expiry(TRADING_EXPIRY)
                .segment(SEGMENT).exchange(EXCHANGE).build();

        double premCE = orderService.placeLeg(shortCE);
        double premPE = orderService.placeLeg(shortPE);
        double costCE = orderService.placeLeg(longCE);
        double costPE = orderService.placeLeg(longPE);

        netPremium = (premCE + premPE) - (costCE + costPE);
        positionOpen = true;

        logger.info("{}: Entry placed. ATM={}, wings=[{}, {}], netPremium={}",
                STRATEGY_NAME, atmStrike, lowerWingStrike, upperWingStrike, netPremium);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks target (50% of premium), stop-loss (loss = premium), time
     * exit, and wing-breach exit.
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

        if (currentPnL >= netPremium * TARGET_PCT) {
            logger.info("{}: Target profit reached. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        if (currentPnL <= -netPremium) {
            logger.info("{}: Stop-loss hit. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        ZonedDateTime now = ZonedDateTime.now(IST);
        if (marketDataProvider.isExpiryDay(UNDERLYING, TRADING_EXPIRY)
                && now.toLocalTime().isAfter(TIME_EXIT)) {
            logger.info("{}: Time exit triggered at {}", STRATEGY_NAME, now.toLocalTime());
            return true;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        if (spot >= upperWingStrike || spot <= lowerWingStrike) {
            logger.info("{}: Wing breach exit. Spot={}", STRATEGY_NAME, spot);
            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Squares off all four legs of the iron butterfly.
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
