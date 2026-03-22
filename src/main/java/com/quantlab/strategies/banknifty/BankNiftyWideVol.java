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
 * BankNiftyWideVol — Long Strangle on BANKNIFTY.
 *
 * <p>Structure: Buy 1 OTM CE (ATM + 200) + Buy 1 OTM PE (ATM - 200).
 * A cheaper alternative to the Long Straddle that profits from a large move
 * in either direction. Both options are purchased out-of-the-money, so the
 * total premium is lower but the breakeven range is wider.</p>
 *
 * <p>Exit conditions:</p>
 * <ul>
 *   <li>Target: 120% of total premium paid</li>
 *   <li>Stop-loss: 40% of total premium paid</li>
 *   <li>Time exit: 13:00 IST (early exit to avoid theta decay)</li>
 *   <li>IV expand exit: implied volatility rises 40% from entry level</li>
 * </ul>
 *
 * @author QuantLab
 */
public class BankNiftyWideVol extends BaseStrategy {

    private static final String STRATEGY_NAME = "BankNiftyWideVol";
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final String EXCHANGE = "NSE";
    private static final int STRIKE_INTERVAL = 100;
    private static final int OTM_OFFSET = 200;
    private static final int LOTS = 1;
    private static final double TARGET_PCT = 1.20;
    private static final double STOP_LOSS_PCT = 0.40;
    private static final double IV_EXPAND_THRESHOLD = 0.40;
    private static final LocalTime TIME_EXIT = LocalTime.of(14, 55);
    private static final LocalTime ENTRY_TIME = LocalTime.of(9, 32);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderService orderService;
    private final MarketDataProvider marketDataProvider;

    private double totalPremiumPaid;
    private double entryIV;
    private boolean positionOpen;

    /**
     * Constructs a new BankNiftyWideVol strategy.
     *
     * @param orderService      the broker-agnostic order service
     * @param marketDataProvider the broker-agnostic market data provider
     */
    public BankNiftyWideVol(OrderService orderService, MarketDataProvider marketDataProvider) {
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
     * Enters the Long Strangle position.
     *
     * <p>Determines the ATM strike from the current spot price, then:
     * <ol>
     *   <li>Buys 1 lot OTM CE at ATM + 200</li>
     *   <li>Buys 1 lot OTM PE at ATM - 200</li>
     * </ol>
     * Records the total premium paid and the entry implied volatility.</p>
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
        int otmCeStrike = atmStrike + OTM_OFFSET;
        int otmPeStrike = atmStrike - OTM_OFFSET;
        String expiry = marketDataProvider.getCurrentMonthExpiry(UNDERLYING);

        double cePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, otmCeStrike, OptionType.CE);
        double pePremium = marketDataProvider.getOptionPremium(UNDERLYING, expiry, otmPeStrike, OptionType.PE);

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(otmCeStrike)
                .optionType(OptionType.CE).side(OrderSide.BUY).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        orderService.placeOrder(Leg.builder()
                .underlying(UNDERLYING).expiry(expiry).strike(otmPeStrike)
                .optionType(OptionType.PE).side(OrderSide.BUY).lots(LOTS)
                .segment(SEGMENT).exchange(EXCHANGE).build());

        totalPremiumPaid = cePremium + pePremium;
        entryIV = (marketDataProvider.getImpliedVolatility(UNDERLYING, expiry, otmCeStrike, OptionType.CE)
                + marketDataProvider.getImpliedVolatility(UNDERLYING, expiry, otmPeStrike, OptionType.PE)) / 2.0;
        positionOpen = true;

        log("Entered WideVol: bought {} CE @{} + {} PE @{}. Total premium={}",
                otmCeStrike, cePremium, otmPeStrike, pePremium, totalPremiumPaid);
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
        int otmCeStrike = atmStrike + OTM_OFFSET;
        int otmPeStrike = atmStrike - OTM_OFFSET;
        double currentIV = (marketDataProvider.getImpliedVolatility(UNDERLYING, expiry, otmCeStrike, OptionType.CE)
                + marketDataProvider.getImpliedVolatility(UNDERLYING, expiry, otmPeStrike, OptionType.PE)) / 2.0;
        if (entryIV > 0 && ((currentIV - entryIV) / entryIV) >= IV_EXPAND_THRESHOLD) {
            log("IV expand exit: entry IV={}, current IV={}, rise={}%",
                    entryIV, currentIV, ((currentIV - entryIV) / entryIV) * 100);
            return true;
        }

        return false;
    }

    /**
     * Exits the entire Long Strangle position by squaring off all legs.
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
        log("Exited WideVol. Realised PnL={}", finalSnapshot.getRealizedPnl());
        positionOpen = false;
    }

    private int roundToStrike(double price) {
        return (int) (Math.round(price / STRIKE_INTERVAL) * STRIKE_INTERVAL);
    }
}
