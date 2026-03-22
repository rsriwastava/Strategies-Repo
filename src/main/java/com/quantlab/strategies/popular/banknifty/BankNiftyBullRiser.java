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
 * BankNiftyBullRiser — Bull Call Spread on BANKNIFTY.
 *
 * <p>Structure: Buy 1 ATM CE + Sell 1 OTM CE (ATM + 300).
 * A net-debit directional strategy that profits from moderate upside movement.
 * Maximum profit is capped at the difference between strikes minus the net
 * premium paid. Maximum loss is limited to the net premium paid.</p>
 *
 * <p>Exit conditions:</p>
 * <ul>
 *   <li>Target: 80% of max profit (strike width - net debit)</li>
 *   <li>Stop-loss: 50% of net premium paid</li>
 *   <li>Time exit: 14:30 IST</li>
 * </ul>
 *
 * @author QuantLab
 */
public class BankNiftyBullRiser extends BaseStrategy {

    private static final String STRATEGY_NAME = "BankNiftyBullRiser";
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final int STRIKE_INTERVAL = 100;
    private static final int OTM_OFFSET = 300;
    private static final int LOTS = 1;
    private static final double TARGET_PCT = 0.80;
    private static final double STOP_LOSS_PCT = 0.50;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double entryNetDebit;
    private double maxProfit;
    private boolean positionOpen;

    /**
     * Constructs a new BankNiftyBullRiser strategy.
     *
     * @param orderService      the broker-agnostic order service
     * @param marketDataProvider the broker-agnostic market data provider
     */
    public BankNiftyBullRiser(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * Enters the Bull Call Spread position.
     *
     * <p>Determines the ATM strike from the current spot price, then:
     * <ol>
     *   <li>Buys 1 lot ATM CE</li>
     *   <li>Sells 1 lot OTM CE at ATM + 300</li>
     * </ol>
     * Records the net debit and computes the maximum profit potential.</p>
     */
    @Override
    public void onEntry() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (now.isBefore(ENTRY_TIME)) {
            log("[{}] Entry blocked — before {} IST", STRATEGY_NAME, ENTRY_TIME);
            return;
        }

        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrike(spot);
        int otmStrike = atmStrike + OTM_OFFSET;
        String expiry = marketDataProvider.getCurrentMonthExpiry(UNDERLYING);

        double atmPremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, atmStrike, OptionType.CE);
        double otmPremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, otmStrike, OptionType.CE);

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING)
                .expiry(expiry)
                .strike(atmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .lots(LOTS)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING)
                .expiry(expiry)
                .strike(otmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.SELL)
                .lots(LOTS)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build());

        entryNetDebit = atmPremium - otmPremium;
        maxProfit = OTM_OFFSET - entryNetDebit;
        positionOpen = true;

        log("Entered BullRiser: bought {} CE @{}, sold {} CE @{}. Net debit={}, max profit={}",
                atmStrike, atmPremium, otmStrike, otmPremium, entryNetDebit, maxProfit);
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

        PositionSnapshot snapshot = orderService.getPositionSnapshot(STRATEGY_NAME);
        double currentPnl = snapshot.getUnrealizedPnl();

        if (maxProfit > 0 && currentPnl >= maxProfit * TARGET_PCT) {
            log("Target hit: PnL {} >= target {}", currentPnl, maxProfit * TARGET_PCT);
            return true;
        }

        if (entryNetDebit > 0 && currentPnl <= -(entryNetDebit * STOP_LOSS_PCT)) {
            log("Stop-loss hit: PnL {} <= SL {}", currentPnl, -(entryNetDebit * STOP_LOSS_PCT));
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
     * Exits the entire Bull Call Spread position by squaring off all legs.
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
        log("Exited BullRiser. Realised PnL={}", finalSnapshot.getRealizedPnl());
        positionOpen = false;
    }

    private int roundToStrike(double price) {
        return (int) (Math.round(price / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
