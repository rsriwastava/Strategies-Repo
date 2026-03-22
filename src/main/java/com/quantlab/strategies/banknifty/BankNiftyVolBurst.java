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
 * BankNiftyVolBurst — Long Straddle on BANKNIFTY.
 *
 * <p>Structure: Buy 1 ATM CE + Buy 1 ATM PE.
 * A pure volatility play that profits from a large move in either direction.
 * The strategy pays a net debit equal to the sum of both ATM premiums and
 * requires the underlying to move beyond the combined premium to profit.</p>
 *
 * <p>Exit conditions:</p>
 * <ul>
 *   <li>Target: 80% of total premium paid</li>
 *   <li>Stop-loss: 35% of total premium paid</li>
 *   <li>Time exit: 13:00 IST (early exit to avoid theta decay)</li>
 *   <li>IV expand exit: implied volatility rises 40% from entry — take profit on vol spike</li>
 * </ul>
 *
 * @author QuantLab
 */
public class BankNiftyVolBurst extends BaseStrategy {

    private static final String STRATEGY_NAME = "BankNiftyVolBurst";
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final int STRIKE_INTERVAL = 100;
    private static final int LOTS = 1;
    private static final double TARGET_PCT = 0.80;
    private static final double STOP_LOSS_PCT = 0.35;
    private static final double IV_EXPAND_THRESHOLD = 0.40;
    private static final LocalTime TIME_EXIT = LocalTime.of(13, 0);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double totalPremiumPaid;
    private double entryIV;
    private boolean positionOpen;

    /**
     * Constructs a new BankNiftyVolBurst strategy.
     *
     * @param orderService      the broker-agnostic order service
     * @param marketDataProvider the broker-agnostic market data provider
     */
    public BankNiftyVolBurst(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * Enters the Long Straddle position.
     *
     * <p>Determines the ATM strike from the current spot price, then:
     * <ol>
     *   <li>Buys 1 lot ATM CE</li>
     *   <li>Buys 1 lot ATM PE</li>
     * </ol>
     * Records the total premium paid and the entry implied volatility.</p>
     */
    @Override
    public void onEntry() {
        double spot = marketDataProvider.getSpotPrice(UNDERLYING);
        int atmStrike = roundToStrike(spot);
        String expiry = marketDataProvider.getCurrentMonthExpiry(UNDERLYING);

        double cePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, atmStrike, OptionType.CE);
        double pePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, atmStrike, OptionType.PE);

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(atmStrike)
                .optionType(OptionType.CE).side(OrderSide.BUY).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(atmStrike)
                .optionType(OptionType.PE).side(OrderSide.BUY).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        totalPremiumPaid = cePremium + pePremium;
        entryIV = marketDataProvider.getImpliedVolatility(UNDERLYING, expiry, atmStrike, OptionType.CE);
        positionOpen = true;

        log("Entered VolBurst: bought {} CE @{} + {} PE @{}. Total premium={}",
                atmStrike, cePremium, atmStrike, pePremium, totalPremiumPaid);
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

        if (totalPremiumPaid > 0 && currentPnl >= totalPremiumPaid * TARGET_PCT) {
            log("Target hit: PnL {} >= target {}", currentPnl, totalPremiumPaid * TARGET_PCT);
            return true;
        }

        if (totalPremiumPaid > 0 && currentPnl <= -(totalPremiumPaid * STOP_LOSS_PCT)) {
            log("Stop-loss hit: PnL {} <= SL {}", currentPnl, -(totalPremiumPaid * STOP_LOSS_PCT));
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
        if (entryIV > 0 && ((currentIV - entryIV) / entryIV) >= IV_EXPAND_THRESHOLD) {
            log("IV expand exit: entry IV={}, current IV={}, rise={}%",
                    entryIV, currentIV, ((currentIV - entryIV) / entryIV) * 100);
            return true;
        }

        return false;
    }

    /**
     * Exits the entire Long Straddle position by squaring off all legs.
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
        log("Exited VolBurst. Realised PnL={}", finalSnapshot.getRealizedPnl());
        positionOpen = false;
    }

    private int roundToStrike(double price) {
        return (int) (Math.round(price / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
