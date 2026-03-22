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
 * BankNiftyPowerStrip — Strip strategy on BANKNIFTY.
 *
 * <p>Structure: Buy 1 ATM CE + Buy 2 ATM PE.
 * A bearish volatility play with CE:PE ratio of 1:2. The strategy profits
 * from a large move in either direction but has a greater reward on the
 * downside due to the extra put lot. Total premium paid is the net debit.</p>
 *
 * <p>Exit conditions:</p>
 * <ul>
 *   <li>Target: 100% of total premium paid</li>
 *   <li>Stop-loss: 40% of total premium paid</li>
 *   <li>IV crush exit: implied volatility drops 30% from entry level</li>
 *   <li>Time exit: 14:30 IST</li>
 * </ul>
 *
 * @author QuantLab
 */
public class BankNiftyPowerStrip extends BaseStrategy {

    private static final String STRATEGY_NAME = "BankNiftyPowerStrip";
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final int STRIKE_INTERVAL = 100;
    private static final int CE_LOTS = 1;
    private static final int PE_LOTS = 2;
    private static final double TARGET_PCT = 1.00;
    private static final double STOP_LOSS_PCT = 0.40;
    private static final double IV_CRUSH_THRESHOLD = 0.30;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 30);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double totalPremiumPaid;
    private double entryIV;
    private boolean positionOpen;

    /**
     * Constructs a new BankNiftyPowerStrip strategy.
     *
     * @param orderService      the broker-agnostic order service
     * @param marketDataProvider the broker-agnostic market data provider
     */
    public BankNiftyPowerStrip(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * Enters the Strip position.
     *
     * <p>Determines the ATM strike from the current spot price, then:
     * <ol>
     *   <li>Buys 1 lot ATM CE</li>
     *   <li>Buys 2 lots ATM PE</li>
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
                .optionType(OptionType.CE).side(OrderSide.BUY).lots(CE_LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(atmStrike)
                .optionType(OptionType.PE).side(OrderSide.BUY).lots(PE_LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        totalPremiumPaid = (CE_LOTS * cePremium) + (PE_LOTS * pePremium);
        entryIV = marketDataProvider.getImpliedVolatility(UNDERLYING, expiry, atmStrike, OptionType.PE);
        positionOpen = true;

        log("Entered PowerStrip: bought {}x {} CE @{} + {}x {} PE @{}. Total premium={}",
                CE_LOTS, atmStrike, cePremium, PE_LOTS, atmStrike, pePremium, totalPremiumPaid);
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
        double currentIV = marketDataProvider.getImpliedVolatility(UNDERLYING, expiry, atmStrike, OptionType.PE);
        if (entryIV > 0 && ((entryIV - currentIV) / entryIV) >= IV_CRUSH_THRESHOLD) {
            log("IV crush exit: entry IV={}, current IV={}, drop={}%",
                    entryIV, currentIV, ((entryIV - currentIV) / entryIV) * 100);
            return true;
        }

        return false;
    }

    /**
     * Exits the entire Strip position by squaring off all legs.
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
        log("Exited PowerStrip. Realised PnL={}", finalSnapshot.getRealizedPnl());
        positionOpen = false;
    }

    private int roundToStrike(double price) {
        return (int) (Math.round(price / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
