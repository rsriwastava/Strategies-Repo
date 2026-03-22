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
 * <b>NiftyWideVol</b> &mdash; Long Strangle.
 *
 * <p>Leg structure:
 * <ul>
 *   <li>Buy 1 OTM CE at ATM + 2 &times; strikeInterval (ATM+100)</li>
 *   <li>Buy 1 OTM PE at ATM &minus; 2 &times; strikeInterval (ATM&minus;100)</li>
 * </ul>
 *
 * <p>A net-debit strategy that is cheaper than a straddle but requires a
 * bigger move to profit. Both legs are bought out-of-the-money, lowering
 * the upfront cost at the expense of a wider breakeven range.
 *
 * <p>Max profit = unlimited. Max loss = total premium paid.
 *
 * <p>Entry: VIX &lt; 13 (low IV) and a very large move is expected.
 *
 * <p>Exit conditions:
 * <ol>
 *   <li>Target 120% return on total premium (higher target due to wider
 *       breakevens)</li>
 *   <li>Stop-loss at 40% of premium</li>
 *   <li>Time exit at 13:00 IST (theta acceleration)</li>
 *   <li>IV expansion &gt; 40% from entry</li>
 * </ol>
 *
 * @author QuantLab
 */
public class NiftyWideVol extends BaseStrategy {

    private static final String STRATEGY_NAME = "NiftyWideVol";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final String UNDERLYING = "NIFTY";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "nextWeek";

    private static final double WIDE_VOL_TARGET = 1.2;
    private static final double WIDE_VOL_SL = 0.4;
    private static final int STRANGLE_OFFSET = 2;
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
    private int otmCEStrike;
    private int otmPEStrike;
    private boolean positionOpen;

    /**
     * Constructs a new NiftyWideVol strategy.
     *
     * @param orderService       broker-agnostic order service
     * @param marketDataProvider broker-agnostic market data provider
     */
    public NiftyWideVol(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * <p>Buys 1 OTM CE (ATM+100) and 1 OTM PE (ATM&minus;100). Entry
     * requires VIX below the threshold.
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
        otmCEStrike = atmStrike + STRANGLE_OFFSET * STRIKE_INTERVAL;
        otmPEStrike = atmStrike - STRANGLE_OFFSET * STRIKE_INTERVAL;

        entryIV = marketDataProvider.getImpliedVolatility(UNDERLYING, atmStrike, OptionType.CE);

        LegOrder longCE = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(otmCEStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .quantity(LOT_QTY)
                .expiry(TRADING_EXPIRY)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build();

        LegOrder longPE = LegOrder.builder()
                .underlying(UNDERLYING)
                .strike(otmPEStrike)
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

        logger.info("{}: Entry placed. OTM_CE={}, OTM_PE={}, totalPremium={}, entryIV={}",
                STRATEGY_NAME, otmCEStrike, otmPEStrike, totalPremium, entryIV);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks target (120% return), stop-loss (40% of premium), time exit
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

        if (currentPnL >= totalPremium * WIDE_VOL_TARGET) {
            logger.info("{}: Target profit reached. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        if (currentPnL <= -(totalPremium * WIDE_VOL_SL)) {
            logger.info("{}: Stop-loss hit. PnL={}", STRATEGY_NAME, currentPnL);
            return true;
        }

        ZonedDateTime now = ZonedDateTime.now(IST);
        if (marketDataProvider.isExpiryDay(UNDERLYING, TRADING_EXPIRY)
                && now.toLocalTime().isAfter(TIME_EXIT)) {
            logger.info("{}: Time exit triggered at {}", STRATEGY_NAME, now.toLocalTime());
            return true;
        }

        // IV expansion exit
        double currentIV = marketDataProvider.getImpliedVolatility(UNDERLYING, otmCEStrike, OptionType.CE);
        if (entryIV > 0 && ((currentIV - entryIV) / entryIV) >= IV_EXPAND_EXIT) {
            logger.info("{}: IV expansion exit. entryIV={}, currentIV={}", STRATEGY_NAME, entryIV, currentIV);
            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Squares off both legs of the long strangle.
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
