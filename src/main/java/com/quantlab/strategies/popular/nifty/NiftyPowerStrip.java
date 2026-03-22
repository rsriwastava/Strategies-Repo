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
 * <b>NiftyPowerStrip</b> &mdash; Strip (bearish-biased long volatility).
 *
 * <p>Leg structure (1:2 CE:PE ratio):
 * <ul>
 *   <li>Buy 1 ATM CE</li>
 *   <li>Buy 2 ATM PE</li>
 * </ul>
 *
 * <p>A net-debit strategy that profits from a big move in either direction,
 * with a bearish bias due to the 1:2 call-to-put ratio. The double put
 * exposure magnifies profits on a sharp decline.
 *
 * <p>Max profit = large on downside (2&times; PE exposure). Max loss = total
 * premium paid.
 *
 * <p>Exit conditions:
 * <ol>
 *   <li>Target 100% return on total premium</li>
 *   <li>Stop-loss at 40% of total premium</li>
 *   <li>Time exit at 14:30 IST on expiry day</li>
 *   <li>IV crush exceeds 30% from entry IV</li>
 * </ol>
 *
 * @author QuantLab
 */
public class NiftyPowerStrip extends BaseStrategy {

    private static final String STRATEGY_NAME = "NiftyPowerStrip";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final String UNDERLYING = "NIFTY";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";

    private static final double STRIP_TARGET_PCT = 1.0;
    private static final double STRIP_SL_PCT = 0.4;
    private static final double IV_CRUSH_EXIT = 0.30;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final int CE_QTY = 1;
    private static final int PE_QTY = 2;

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double totalPremium;
    private double entryIV;
    private int atmStrike;
    private boolean positionOpen;

    /**
     * Constructs a new NiftyPowerStrip strategy.
     *
     * @param orderService       broker-agnostic order service
     * @param marketDataProvider broker-agnostic market data provider
     */
    public NiftyPowerStrip(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * <p>Buys 1 ATM CE and 2 ATM PE at the same strike to construct
     * the strip.
     */
    @Override
    public void onEntry() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(ENTRY_TIME)) {
            logger.info("[{}] Entry blocked — before {} IST", STRATEGY_NAME, ENTRY_TIME);
            return;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        atmStrike = roundToStrikeInterval(spot);

        entryIV = marketDataProvider.getImpliedVolatility(UNDERLYING, atmStrike, OptionType.PE);

        LegOrder longCE = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(atmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .quantity(CE_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        LegOrder longPE = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(atmStrike)
                .optionType(OptionType.PE)
                .side(OrderSide.BUY)
                .quantity(PE_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        double cePremium = orderService.placeLeg(longCE);
        double pePremium = orderService.placeLeg(longPE);

        totalPremium = (cePremium * CE_QTY) + (pePremium * PE_QTY);
        positionOpen = true;

        logger.info("{}: Entry placed. ATM={}, totalPremium={}, entryIV={}",
                STRATEGY_NAME, atmStrike, totalPremium, entryIV);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks target (100% return), stop-loss (40% of premium), time exit,
     * and IV crush exit (30% drop).
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

        if (currentPnL >= totalPremium * STRIP_TARGET_PCT) {
            logger.info("{}: Target profit reached. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        if (currentPnL <= -(totalPremium * STRIP_SL_PCT)) {
            logger.info("{}: Stop-loss hit. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        ZonedDateTime now = ZonedDateTime.now(IST);
        if (marketDataProvider.isExpiryDay(UNDERLYING, TRADING_EXPIRY)
                && now.toLocalTime().isAfter(TIME_EXIT)) {
            logger.info("{}: Time exit triggered at {}", STRATEGY_NAME, now.toLocalTime());
            return true;
        }

        double currentIV = marketDataProvider.getImpliedVolatility(UNDERLYING, atmStrike, OptionType.PE);
        if (entryIV > 0 && ((entryIV - currentIV) / entryIV) >= IV_CRUSH_EXIT) {
            logger.info("{}: IV crush exit. entryIV={}, currentIV={}", STRATEGY_NAME, entryIV, currentIV);
            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Squares off all open legs of the strip.
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
