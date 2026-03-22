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
 * <b>NiftyPutVortex</b> &mdash; Put Backspread (Long Put Ratio Backspread).
 *
 * <p>Leg structure (1:2 sell:buy ratio):
 * <ul>
 *   <li>Sell 1 ATM PE</li>
 *   <li>Buy 2 OTM PE at ATM &minus; 2 &times; strikeInterval (ATM&minus;100)</li>
 * </ul>
 *
 * <p>A net-debit strategy that profits from a sharp downside move. Maximum
 * loss is limited at the short strike. Maximum profit is large on the
 * downside (limited by zero).
 *
 * <p>Entry trigger: VIX &lt; 15 with a bearish bias.
 *
 * <p>Exit conditions:
 * <ol>
 *   <li>Target profit of 100% of max-loss</li>
 *   <li>Stop-loss at 50% of max-loss</li>
 *   <li>Time-based exit at 14:30 IST on expiry day</li>
 *   <li>IV drops more than 20% from entry IV</li>
 * </ol>
 *
 * @author QuantLab
 */
public class NiftyPutVortex extends BaseStrategy {

    private static final String STRATEGY_NAME = "NiftyPutVortex";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final String UNDERLYING = "NIFTY";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";

    private static final double PUT_VORTEX_TARGET_PCT = 1.0;
    private static final double PUT_VORTEX_SL_PCT = 0.5;
    private static final int OTM_OFFSET_MULTIPLIER = 2;
    private static final double VIX_ENTRY_THRESHOLD = 15.0;
    private static final double IV_DROP_EXIT_PCT = 0.20;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final int SELL_QTY = 1;
    private static final int BUY_QTY = 2;

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double entryIV;
    private double maxLoss;
    private boolean positionOpen;

    /**
     * Constructs a new NiftyPutVortex strategy.
     *
     * @param orderService       broker-agnostic order service
     * @param marketDataProvider broker-agnostic market data provider
     */
    public NiftyPutVortex(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * <p>Places the put backspread: sells 1 ATM PE and buys 2 OTM PE
     * (ATM &minus; 100). Entry requires VIX below the threshold.
     */
    @Override
    public void onEntry() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(ENTRY_TIME)) {
            logger.info("[{}] Entry blocked — before {} IST", STRATEGY_NAME, ENTRY_TIME);
            return;
        }

        double vix = marketDataProvider.getVIX();
        if (vix >= VIX_ENTRY_THRESHOLD) {
            logger.info("{}: VIX {} >= threshold {}. Skipping entry.", STRATEGY_NAME, vix, VIX_ENTRY_THRESHOLD);
            return;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrikeInterval(spot);
        int otmStrike = atmStrike - OTM_OFFSET_MULTIPLIER * STRIKE_INTERVAL;

        entryIV = marketDataProvider.getImpliedVolatility(UNDERLYING, atmStrike, OptionType.PE);

        LegOrder shortLeg = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(atmStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.SELL)
                .quantity(SELL_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        LegOrder longLeg = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(otmStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.BUY)
                .quantity(BUY_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        double premiumReceived = orderService.placeLeg(shortLeg);
        double premiumPaid = orderService.placeLeg(longLeg);

        double netDebit = (premiumPaid * BUY_QTY) - (premiumReceived * SELL_QTY);
        int strikeWidth = atmStrike - otmStrike;
        maxLoss = netDebit + strikeWidth;
        positionOpen = true;

        logger.info("{}: Entry placed. ATM={}, OTM={}, netDebit={}, maxLoss={}, entryIV={}",
                STRATEGY_NAME, atmStrike, otmStrike, netDebit, maxLoss, entryIV);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks target profit, stop-loss, time exit, and IV-drop exit.
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

        if (currentPnL >= maxLoss * PUT_VORTEX_TARGET_PCT) {
            logger.info("{}: Target profit reached. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        if (currentPnL <= -(maxLoss * PUT_VORTEX_SL_PCT)) {
            logger.info("{}: Stop-loss hit. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        ZonedDateTime now = ZonedDateTime.now(IST);
        if (marketDataProvider.isExpiryDay(UNDERLYING, TRADING_EXPIRY)
                && now.toLocalTime().isAfter(TIME_EXIT)) {
            logger.info("{}: Time exit triggered at {}", STRATEGY_NAME, now.toLocalTime());
            return true;
        }

        double currentIV = marketDataProvider.getImpliedVolatility(
                UNDERLYING, snapshot.getShortStrike(), OptionType.PE);
        if (entryIV > 0 && ((entryIV - currentIV) / entryIV) >= IV_DROP_EXIT_PCT) {
            logger.info("{}: IV drop exit. entryIV={}, currentIV={}", STRATEGY_NAME, entryIV, currentIV);
            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Squares off all open legs of the put backspread.
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
