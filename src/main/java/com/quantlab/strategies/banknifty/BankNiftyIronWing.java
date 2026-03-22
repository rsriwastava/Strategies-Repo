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
 * BankNiftyIronWing — Iron Butterfly on BANKNIFTY.
 *
 * <p>Structure:
 * <ul>
 *   <li>Sell 1 ATM CE</li>
 *   <li>Sell 1 ATM PE</li>
 *   <li>Buy 1 OTM CE (ATM + 200)</li>
 *   <li>Buy 1 OTM PE (ATM - 200)</li>
 * </ul>
 * A net-credit strategy that profits from range-bound markets. The wing width
 * is 200 points on each side. Maximum profit equals the net credit received;
 * maximum loss is wing width minus net credit.</p>
 *
 * <p>Exit conditions:</p>
 * <ul>
 *   <li>Target: 50% of net premium collected</li>
 *   <li>Stop-loss: loss equals net premium collected (1x)</li>
 *   <li>Time exit: 14:30 IST</li>
 * </ul>
 *
 * @author QuantLab
 */
public class BankNiftyIronWing extends BaseStrategy {

    private static final String STRATEGY_NAME = "BankNiftyIronWing";
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final int STRIKE_INTERVAL = 100;
    private static final int WING_WIDTH = 200;
    private static final int LOTS = 1;
    private static final double TARGET_PCT = 0.50;
    private static final double STOP_LOSS_MULTIPLIER = 1.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double netCreditReceived;
    private boolean positionOpen;

    /**
     * Constructs a new BankNiftyIronWing strategy.
     *
     * @param orderService      the broker-agnostic order service
     * @param marketDataProvider the broker-agnostic market data provider
     */
    public BankNiftyIronWing(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * Enters the Iron Butterfly position.
     *
     * <p>Determines the ATM strike, then places four legs:
     * sells ATM CE and ATM PE, buys wing CE and wing PE at +/- 200 points.</p>
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
        int upperWing = atmStrike + WING_WIDTH;
        int lowerWing = atmStrike - WING_WIDTH;
        String expiry = marketDataProvider.getCurrentMonthExpiry(UNDERLYING);

        double atmCePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, atmStrike, OptionType.CE);
        double atmPePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, atmStrike, OptionType.PE);
        double otmCePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, upperWing, OptionType.CE);
        double otmPePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, lowerWing, OptionType.PE);

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(atmStrike)
                .optionType(OptionType.CE).side(OrderSide.SELL).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(atmStrike)
                .optionType(OptionType.PE).side(OrderSide.SELL).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(upperWing)
                .optionType(OptionType.CE).side(OrderSide.BUY).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(lowerWing)
                .optionType(OptionType.PE).side(OrderSide.BUY).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        netCreditReceived = (atmCePremium + atmPePremium) - (otmCePremium + otmPePremium);
        positionOpen = true;

        log("Entered IronWing: sold ATM {} CE @{} + PE @{}, bought {} CE @{} + {} PE @{}. Net credit={}",
                atmStrike, atmCePremium, atmPePremium, upperWing, otmCePremium,
                lowerWing, otmPePremium, netCreditReceived);
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

        if (netCreditReceived > 0 && currentPnl >= netCreditReceived * TARGET_PCT) {
            log("Target hit: PnL {} >= target {}", currentPnl, netCreditReceived * TARGET_PCT);
            return true;
        }

        if (netCreditReceived > 0 && currentPnl <= -(netCreditReceived * STOP_LOSS_MULTIPLIER)) {
            log("Stop-loss hit: PnL {} <= SL {}", currentPnl, -(netCreditReceived * STOP_LOSS_MULTIPLIER));
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
     * Exits the entire Iron Butterfly position by squaring off all legs.
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
        log("Exited IronWing. Realised PnL={}", finalSnapshot.getRealizedPnl());
        positionOpen = false;
    }

    private int roundToStrike(double price) {
        return (int) (Math.round(price / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
