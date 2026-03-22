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
 * BankNiftyIronFortress — Iron Condor on BANKNIFTY.
 *
 * <p>Structure:
 * <ul>
 *   <li>Sell 1 CE at ATM + 200 (short call)</li>
 *   <li>Sell 1 PE at ATM - 200 (short put)</li>
 *   <li>Buy 1 CE at ATM + 400 (long call wing)</li>
 *   <li>Buy 1 PE at ATM - 400 (long put wing)</li>
 * </ul>
 * A net-credit strategy with a wider profit zone than the Iron Butterfly.
 * Short strikes are offset 200 points from ATM; long wings are offset 400
 * points, giving a 200-point wing width on each side.</p>
 *
 * <p>Exit conditions:</p>
 * <ul>
 *   <li>Target: 50% of net premium collected</li>
 *   <li>Stop-loss: loss equals 2x net premium collected</li>
 *   <li>Time exit: 14:30 IST</li>
 * </ul>
 *
 * @author QuantLab
 */
public class BankNiftyIronFortress extends BaseStrategy {

    private static final String STRATEGY_NAME = "BankNiftyIronFortress";
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final int STRIKE_INTERVAL = 100;
    private static final int SHORT_OFFSET = 200;
    private static final int LONG_OFFSET = 400;
    private static final int LOTS = 1;
    private static final double TARGET_PCT = 0.50;
    private static final double STOP_LOSS_MULTIPLIER = 2.0;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double netCreditReceived;
    private boolean positionOpen;

    /**
     * Constructs a new BankNiftyIronFortress strategy.
     *
     * @param orderService      the broker-agnostic order service
     * @param marketDataProvider the broker-agnostic market data provider
     */
    public BankNiftyIronFortress(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * Enters the Iron Condor position.
     *
     * <p>Determines the ATM strike, then places four legs with the short strikes
     * at ATM +/- 200 and long wings at ATM +/- 400.</p>
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
        int shortCeStrike = atmStrike + SHORT_OFFSET;
        int shortPeStrike = atmStrike - SHORT_OFFSET;
        int longCeStrike = atmStrike + LONG_OFFSET;
        int longPeStrike = atmStrike - LONG_OFFSET;
        String expiry = marketDataProvider.getCurrentMonthExpiry(UNDERLYING);

        double shortCePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, shortCeStrike, OptionType.CE);
        double shortPePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, shortPeStrike, OptionType.PE);
        double longCePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, longCeStrike, OptionType.CE);
        double longPePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, longPeStrike, OptionType.PE);

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(shortCeStrike)
                .optionType(OptionType.CE).side(OrderSide.SELL).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(shortPeStrike)
                .optionType(OptionType.PE).side(OrderSide.SELL).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(longCeStrike)
                .optionType(OptionType.CE).side(OrderSide.BUY).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(longPeStrike)
                .optionType(OptionType.PE).side(OrderSide.BUY).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        netCreditReceived = (shortCePremium + shortPePremium) - (longCePremium + longPePremium);
        positionOpen = true;

        log("Entered IronFortress: sold {} CE @{} + {} PE @{}, bought {} CE @{} + {} PE @{}. Net credit={}",
                shortCeStrike, shortCePremium, shortPeStrike, shortPePremium,
                longCeStrike, longCePremium, longPeStrike, longPePremium, netCreditReceived);
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
     * Exits the entire Iron Condor position by squaring off all legs.
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
        log("Exited IronFortress. Realised PnL={}", finalSnapshot.getRealizedPnl());
        positionOpen = false;
    }

    private int roundToStrike(double price) {
        return (int) (Math.round(price / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
