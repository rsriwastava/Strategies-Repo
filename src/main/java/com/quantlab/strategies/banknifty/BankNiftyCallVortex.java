package com.quantlab.strategies.banknifty;

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
 * BankNiftyCallVortex — Call Backspread on BANKNIFTY.
 *
 * <p>Structure: Sell 1 ATM CE + Buy 2 OTM CE (ATM + 200).
 * This is a net-debit strategy that profits from a sharp upside move.
 * The 1:2 lot ratio gives unlimited upside potential with capped downside
 * risk equal to the net debit paid plus the difference between strikes.</p>
 *
 * <p>Exit conditions:</p>
 * <ul>
 *   <li>Target: 100% of net premium paid</li>
 *   <li>Stop-loss: 50% of net premium paid</li>
 *   <li>Time exit: 14:30 IST</li>
 *   <li>IV drop exit: implied volatility drops 20% from entry level</li>
 * </ul>
 *
 * @author QuantLab
 */
public class BankNiftyCallVortex extends BaseStrategy {

    private static final String STRATEGY_NAME = "BankNiftyCallVortex";
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final int STRIKE_INTERVAL = 100;
    private static final int OTM_OFFSET = 200;
    private static final int SHORT_LOTS = 1;
    private static final int LONG_LOTS = 2;
    private static final double TARGET_PCT = 1.00;
    private static final double STOP_LOSS_PCT = 0.50;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final double IV_DROP_THRESHOLD = 0.20;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double entryNetPremium;
    private double entryIV;
    private boolean positionOpen;

    /**
     * Constructs a new BankNiftyCallVortex strategy.
     *
     * @param orderService      the broker-agnostic order service
     * @param marketDataProvider the broker-agnostic market data provider
     */
    public BankNiftyCallVortex(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * Enters the Call Backspread position.
     *
     * <p>Determines the ATM strike from the current spot price, then:
     * <ol>
     *   <li>Sells 1 lot ATM CE</li>
     *   <li>Buys 2 lots OTM CE at ATM + 200</li>
     * </ol>
     * Records the net premium paid and the entry implied volatility.</p>
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
                .side(OrderSide.SELL)
                .lots(SHORT_LOTS)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING)
                .expiry(expiry)
                .strike(otmStrike)
                .optionType(OptionType.CE)
                .side(OrderSide.BUY)
                .lots(LONG_LOTS)
                .segment(SEGMENT)
                .exchange(EXCHANGE)
                .build());

        entryNetPremium = (LONG_LOTS * otmPremium) - (SHORT_LOTS * atmPremium);
        entryIV = marketDataProvider.getImpliedVolatility(UNDERLYING, expiry, atmStrike, OptionType.CE);
        positionOpen = true;

        log("Entered CallVortex: sold {}x {} CE @{}, bought {}x {} CE @{}. Net debit={}",
                SHORT_LOTS, atmStrike, atmPremium, LONG_LOTS, otmStrike, otmPremium, entryNetPremium);
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

        if (entryNetPremium > 0 && currentPnl >= entryNetPremium * TARGET_PCT) {
            log("Target hit: PnL {} >= target {}", currentPnl, entryNetPremium * TARGET_PCT);
            return true;
        }

        if (entryNetPremium > 0 && currentPnl <= -(entryNetPremium * STOP_LOSS_PCT)) {
            log("Stop-loss hit: PnL {} <= SL {}", currentPnl, -(entryNetPremium * STOP_LOSS_PCT));
            return true;
        }

        ZonedDateTime now = ZonedDateTime.now(IST);
        if (!now.toLocalTime().isBefore(TIME_EXIT)) {
            log("Time exit triggered at {}", now.toLocalTime());
            return true;
        }

        String expiry = marketDataProvider.getCurrentMonthExpiry(UNDERLYING);
        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrike(spot);
        double currentIV = marketDataProvider.getImpliedVolatility(UNDERLYING, expiry, atmStrike, OptionType.CE);
        if (entryIV > 0 && ((entryIV - currentIV) / entryIV) >= IV_DROP_THRESHOLD) {
            log("IV drop exit: entry IV={}, current IV={}, drop={}%",
                    entryIV, currentIV, ((entryIV - currentIV) / entryIV) * 100);
            return true;
        }

        return false;
    }

    /**
     * Exits the entire Call Backspread position by squaring off all legs.
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
        log("Exited CallVortex. Realised PnL={}", finalSnapshot.getRealizedPnl());
        positionOpen = false;
    }

    /**
     * Rounds the given price to the nearest strike interval.
     *
     * @param price the raw price
     * @return the nearest valid strike price
     */
    private int roundToStrike(double price) {
        return (int) (Math.round(price / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
