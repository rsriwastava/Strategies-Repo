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
 * <b>NiftyIronFortress</b> &mdash; Iron Condor.
 *
 * <p>Leg structure:
 * <ul>
 *   <li>Sell 1 OTM CE at ATM + 2 &times; strikeInterval (ATM+100)</li>
 *   <li>Sell 1 OTM PE at ATM &minus; 2 &times; strikeInterval (ATM&minus;100)</li>
 *   <li>Buy 1 far OTM CE at ATM + 4 &times; strikeInterval (ATM+200)</li>
 *   <li>Buy 1 far OTM PE at ATM &minus; 4 &times; strikeInterval (ATM&minus;200)</li>
 * </ul>
 *
 * <p>A net-credit strategy with a wider profit zone than an iron butterfly.
 * Profits when the underlying stays within the short strikes.
 *
 * <p>Max profit = net premium. Max loss = spread width &minus; premium.
 *
 * <p>Entry: elevated IV, expecting range-bound market.
 *
 * <p>Exit conditions:
 * <ol>
 *   <li>Target 50% of premium received</li>
 *   <li>Stop-loss at 2&times; premium received</li>
 *   <li>Time exit at 14:30 IST on expiry day</li>
 *   <li>Either short strike is breached</li>
 * </ol>
 *
 * @author QuantLab
 */
public class NiftyIronFortress extends BaseStrategy {

    private static final String STRATEGY_NAME = "NiftyIronFortress";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final String UNDERLYING = "NIFTY";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";

    private static final int SHORT_OFFSET = 2;
    private static final int LONG_OFFSET = 4;
    private static final double TARGET_PCT = 0.5;
    private static final double SL_MULTIPLIER = 2.0;
    private static final double VIX_ENTRY_THRESHOLD = 15.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 30);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final int LOT_QTY = 1;

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double netPremium;
    private int shortCEStrike;
    private int shortPEStrike;
    private boolean positionOpen;

    /**
     * Constructs a new NiftyIronFortress strategy.
     *
     * @param orderService       broker-agnostic order service
     * @param marketDataProvider broker-agnostic market data provider
     */
    public NiftyIronFortress(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * <p>Sells OTM CE and OTM PE, buys far OTM CE and far OTM PE to
     * construct the iron condor. Entry requires elevated VIX.
     */
    @Override
    public void onEntry() {
        double vix = marketDataProvider.getVIX();
        if (vix <= VIX_ENTRY_THRESHOLD) {
            logger.info("{}: VIX {} <= threshold {}. Skipping entry.", STRATEGY_NAME, vix, VIX_ENTRY_THRESHOLD);
            return;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrikeInterval(spot);

        shortCEStrike = atmStrike + SHORT_OFFSET * STRIKE_INTERVAL;
        shortPEStrike = atmStrike - SHORT_OFFSET * STRIKE_INTERVAL;
        int longCEStrike = atmStrike + LONG_OFFSET * STRIKE_INTERVAL;
        int longPEStrike = atmStrike - LONG_OFFSET * STRIKE_INTERVAL;

        LegOrder sellCE = LegOrder.builder()
                .underlying(UNDERLYING).strike(shortCEStrike).optionType(OptionType.CE)
                .side(OrderSide.SELL).quantity(LOT_QTY).expiry(TRADING_EXPIRY)
                .segment(SEGMENT).exchange(EXCHANGE).build();

        LegOrder sellPE = LegOrder.builder()
                .underlying(UNDERLYING).strike(shortPEStrike).optionType(OptionType.PE)
                .side(OrderSide.SELL).quantity(LOT_QTY).expiry(TRADING_EXPIRY)
                .segment(SEGMENT).exchange(EXCHANGE).build();

        LegOrder buyCE = LegOrder.builder()
                .underlying(UNDERLYING).strike(longCEStrike).optionType(OptionType.CE)
                .side(OrderSide.BUY).quantity(LOT_QTY).expiry(TRADING_EXPIRY)
                .segment(SEGMENT).exchange(EXCHANGE).build();

        LegOrder buyPE = LegOrder.builder()
                .underlying(UNDERLYING).strike(longPEStrike).optionType(OptionType.PE)
                .side(OrderSide.BUY).quantity(LOT_QTY).expiry(TRADING_EXPIRY)
                .segment(SEGMENT).exchange(EXCHANGE).build();

        double premSellCE = orderService.placeLeg(sellCE);
        double premSellPE = orderService.placeLeg(sellPE);
        double premBuyCE = orderService.placeLeg(buyCE);
        double premBuyPE = orderService.placeLeg(buyPE);

        netPremium = (premSellCE + premSellPE) - (premBuyCE + premBuyPE);
        positionOpen = true;

        logger.info("{}: Entry placed. shorts=[{}, {}], longs=[{}, {}], netPremium={}",
                STRATEGY_NAME, shortPEStrike, shortCEStrike, longPEStrike, longCEStrike, netPremium);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks target (50% premium), stop-loss (2x premium), time exit,
     * and short-strike breach.
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

        if (currentPnL <= -(netPremium * SL_MULTIPLIER)) {
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
        if (spot >= shortCEStrike || spot <= shortPEStrike) {
            logger.info("{}: Short strike breached. Spot={}", STRATEGY_NAME, spot);
            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Squares off all four legs of the iron condor.
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
