package com.quantlab.strategies.popular.banknifty;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.model.Leg;
import com.quantlab.strategies.core.model.OptionType;
import com.quantlab.strategies.core.model.OrderSide;
import com.quantlab.strategies.core.model.PositionSnapshot;
import com.quantlab.strategies.core.service.MarketDataProvider;
import com.quantlab.strategies.core.service.OrderService;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * BankNiftyCallAscent — Call Ladder (Long Call Ladder) on BANKNIFTY.
 *
 * <p>Structure:
 * <ul>
 *   <li>Buy 1 ATM CE</li>
 *   <li>Sell 1 OTM CE at ATM + 200</li>
 *   <li>Sell 1 far OTM CE at ATM + 400</li>
 * </ul>
 * Profits when the underlying stays in a range or moves moderately upward.
 * The two short calls reduce the cost of the long call but introduce
 * unlimited upside risk above the highest strike. Net debit is typically
 * lower than a plain call spread.</p>
 *
 * <p>Exit conditions:</p>
 * <ul>
 *   <li>Target: 80% of max profit (occurs at ATM + 200 at expiry)</li>
 *   <li>Stop-loss: spot breaks above ATM + 400 by 100 points</li>
 *   <li>Premium stop-loss: 50% of net debit paid</li>
 *   <li>Time exit: 14:30 IST</li>
 * </ul>
 *
 * @author QuantLab
 */
public class BankNiftyCallAscent extends BaseStrategy {

    private static final String STRATEGY_NAME = "BankNiftyCallAscent";
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final int STRIKE_INTERVAL = 100;
    private static final int FIRST_OTM_OFFSET = 200;
    private static final int SECOND_OTM_OFFSET = 400;
    private static final int SPOT_BREACH_BUFFER = 100;
    private static final int LOTS = 1;
    private static final double TARGET_PCT = 0.80;
    private static final double PREMIUM_SL_PCT = 0.50;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double entryNetDebit;
    private int entryAtmStrike;
    private int farOtmStrike;
    private double maxProfit;
    private boolean positionOpen;

    /**
     * Constructs a new BankNiftyCallAscent strategy.
     *
     * @param orderService      the broker-agnostic order service
     * @param marketDataProvider the broker-agnostic market data provider
     */
    public BankNiftyCallAscent(OrderService orderService, MarketDataProvider marketDataProvider) {
        super(buildConfig());
        this.orderService = orderService;
        this.marketDataProvider = marketDataProvider;
        this.positionOpen = false;
    }

    private static StrategyConfig buildConfig() {
        return StrategyConfig.builder()
                .strategyName(STRATEGY_NAME)
                .underlying(UNDERLYING)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .strikeInterval(STRIKE_INTERVAL)
                .expiryPolicy("currentMonth")
                .build();
    }

    /**
     * Enters the Call Ladder position.
     *
     * <p>Determines the ATM strike from the current spot price, then:
     * <ol>
     *   <li>Buys 1 lot ATM CE</li>
     *   <li>Sells 1 lot OTM CE at ATM + 200</li>
     *   <li>Sells 1 lot far OTM CE at ATM + 400</li>
     * </ol>
     * Records the net debit, entry ATM strike, and computes max profit.</p>
     */
    @Override
    public void onEntry() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (now.isBefore(ENTRY_TIME)) {
            log("[{}] Entry blocked — before {} IST", STRATEGY_NAME, ENTRY_TIME);
            return;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        entryAtmStrike = roundToStrike(spot);
        int firstOtmStrike = entryAtmStrike + FIRST_OTM_OFFSET;
        farOtmStrike = entryAtmStrike + SECOND_OTM_OFFSET;
        String expiry = marketDataProvider.getCurrentMonthExpiry(UNDERLYING);

        double atmPremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, entryAtmStrike, OptionType.CE);
        double firstOtmPremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, firstOtmStrike, OptionType.CE);
        double farOtmPremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, farOtmStrike, OptionType.CE);

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(entryAtmStrike)
                .optionType(OptionType.CE).side(OrderSide.BUY).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(firstOtmStrike)
                .optionType(OptionType.CE).side(OrderSide.SELL).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(farOtmStrike)
                .optionType(OptionType.CE).side(OrderSide.SELL).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        entryNetDebit = atmPremium - firstOtmPremium - farOtmPremium;
        maxProfit = FIRST_OTM_OFFSET - entryNetDebit;
        positionOpen = true;

        log("Entered CallAscent: bought {} CE @{}, sold {} CE @{}, sold {} CE @{}. Net debit={}, max profit={}",
                entryAtmStrike, atmPremium, firstOtmStrike, firstOtmPremium,
                farOtmStrike, farOtmPremium, entryNetDebit, maxProfit);
    }

    /**
     * Evaluates whether the position should be exited.
     *
     * @return {@code true} if any exit condition is met
     */
    @Override
    public boolean shouldExit() {
        if (!positionOpen) {
            return false;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);

        if (spot > farOtmStrike + SPOT_BREACH_BUFFER) {
            log("Spot breach SL: spot {} > far OTM {} + buffer {}",
                    spot, farOtmStrike, SPOT_BREACH_BUFFER);
            return true;
        }

        PositionSnapshot snapshot = orderService.getPositionSnapshot(STRATEGY_NAME);
        double currentPnl = snapshot.getUnrealizedPnl();

        if (maxProfit > 0 && currentPnl >= maxProfit * TARGET_PCT) {
            log("Target hit: PnL {} >= target {}", currentPnl, maxProfit * TARGET_PCT);
            return true;
        }

        if (entryNetDebit > 0 && currentPnl <= -(entryNetDebit * PREMIUM_SL_PCT)) {
            log("Premium SL hit: PnL {} <= SL {}", currentPnl, -(entryNetDebit * PREMIUM_SL_PCT));
            return true;
        }

        ZonedDateTime now = ZonedDateTime.now(IST);
        if (!now.toLocalTime().isBefore(TIME_EXIT)) {
            log("Time exit triggered at {}", now.toLocalTime());
            return true;
        }

        return false;
    }

    /**
     * Exits the entire Call Ladder position by squaring off all legs.
     */
    @Override
    public void onExit() {
        if (!positionOpen) {
            return;
        }

        List<Leg> openLegs = orderService.getOpenLegs(STRATEGY_NAME);
        for (Leg leg : openLegs) {
            orderService.squareOff(leg);
        }

        PositionSnapshot finalSnapshot = orderService.getPositionSnapshot(STRATEGY_NAME);
        log("Exited CallAscent. Realised PnL={}", finalSnapshot.getRealizedPnl());
        positionOpen = false;
    }

    private int roundToStrike(double price) {
        return (int) (Math.round(price / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
