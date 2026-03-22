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
 * <b>NiftyVolBurst</b> &mdash; Long Straddle.
 *
 * <p>Leg structure:
 * <ul>
 *   <li>Buy 1 ATM CE</li>
 *   <li>Buy 1 ATM PE (same strike)</li>
 * </ul>
 *
 * <p>A net-debit strategy that profits from a large move in either direction.
 * Both legs are purchased at the same ATM strike, creating a symmetric
 * payoff profile around the strike price.
 *
 * <p>Max profit = unlimited. Max loss = total premium paid (both legs expire
 * worthless if the underlying stays exactly at the strike).
 *
 * <p>Entry: VIX &lt; 13 (low IV) and a big move is expected (pre-event such
 * as RBI policy, Union budget, or earnings).
 *
 * <p>Exit conditions:
 * <ol>
 *   <li>Target 80% return on total premium</li>
 *   <li>Stop-loss at 35% of premium (theta decay erodes value quickly)</li>
 *   <li>Time exit at 13:00 IST (theta accelerates in last 2 hours)</li>
 *   <li>IV expands &gt; 40% from entry (take vol-expansion profit)</li>
 * </ol>
 *
 * @author QuantLab
 */
public class NiftyVolBurst extends BaseStrategy {

    private static final String STRATEGY_NAME = "NiftyVolBurst";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final String UNDERLYING = "NIFTY";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";

    private static final double VOL_BURST_TARGET = 0.8;
    private static final double VOL_BURST_SL = 0.35;
    private static final double IV_EXPAND_EXIT = 0.40;
    private static final double VIX_ENTRY_THRESHOLD = 13.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final int LOT_QTY = 1;

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double totalPremium;
    private double entryIV;
    private int atmStrike;
    private boolean positionOpen;

    /**
     * Constructs a new NiftyVolBurst strategy.
     *
     * @param orderService       broker-agnostic order service
     * @param marketDataProvider broker-agnostic market data provider
     */
    public NiftyVolBurst(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * <p>Buys 1 ATM CE and 1 ATM PE at the same strike. Entry requires
     * VIX below the threshold (low IV environment).
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
        atmStrike = roundToStrikeInterval(spot);

        entryIV = marketDataProvider.getImpliedVolatility(UNDERLYING, atmStrike, OptionType.CE);

        LegOrder longCE = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(atmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        LegOrder longPE = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(atmStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.BUY)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        double cePremium = orderService.placeLeg(longCE);
        double pePremium = orderService.placeLeg(longPE);

        totalPremium = cePremium + pePremium;
        positionOpen = true;

        logger.info("{}: Entry placed. ATM={}, totalPremium={}, entryIV={}",
                STRATEGY_NAME, atmStrike, totalPremium, entryIV);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks target (80% return), stop-loss (35% of premium), time exit
     * (13:00 IST), and IV expansion exit (40% increase).
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

        if (currentPnL >= totalPremium * VOL_BURST_TARGET) {
            logger.info("{}: Target profit reached. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        if (currentPnL <= -(totalPremium * VOL_BURST_SL)) {
            logger.info("{}: Stop-loss hit. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        ZonedDateTime now = ZonedDateTime.now(IST);
        if (marketDataProvider.isExpiryDay(UNDERLYING, TRADING_EXPIRY)
                && now.toLocalTime().isAfter(TIME_EXIT)) {
            logger.info("{}: Time exit triggered at {}", STRATEGY_NAME, now.toLocalTime());
            return true;
        }

        // IV expansion exit: take profit if IV has expanded significantly
        double currentIV = marketDataProvider.getImpliedVolatility(UNDERLYING, atmStrike, OptionType.CE);
        if (entryIV > 0 && ((currentIV - entryIV) / entryIV) >= IV_EXPAND_EXIT) {
            logger.info("{}: IV expansion exit. entryIV={}, currentIV={}", STRATEGY_NAME, entryIV, currentIV);
            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Squares off both legs of the long straddle.
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
