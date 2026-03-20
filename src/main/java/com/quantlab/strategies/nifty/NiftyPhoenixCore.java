package com.quantlab.strategies.nifty;

import com.quantlab.strategies.core.*;

/**
 * NiftyPhoenixCore -- ATM straddle sell with phoenix re-entry for NIFTY.
 *
 * <h3>Overview:</h3>
 * <p>Enters a 2-leg ATM straddle (CE SELL + PE SELL) at the nearest strike
 * rounded to 100. On stop-loss exit, the strategy re-enters automatically
 * (like a phoenix rising) at the new ATM, subject to max re-entry count
 * and cooldown constraints.</p>
 *
 * <h3>ATM Rounding:</h3>
 * <p>Strike is rounded to the nearest 100 (optional, enabled by default).
 * This reduces unnecessary churn when spot oscillates near a 50-point strike
 * boundary. Can be disabled by overriding {@code ATM_ROUNDING}.</p>
 *
 * <h3>Entry:</h3>
 * <ul>
 *   <li>Compute ATM rounded to nearest 100</li>
 *   <li>Place CE SELL + PE SELL at that strike</li>
 * </ul>
 *
 * <h3>Exit (phoenixExit):</h3>
 * <ul>
 *   <li>PNL-based stop-loss (configurable)</li>
 *   <li>Time-based (market close)</li>
 *   <li>Manual exit flag</li>
 * </ul>
 *
 * <h3>Re-entry:</h3>
 * <p>After SL exit, the strategy re-enters at the new ATM after cooldown
 * expires. Max re-entries and cooldown are enforced by BaseStrategy.</p>
 */
public class NiftyPhoenixCore extends BaseStrategy {

    private static final String UNDERLYING = "NIFTY";
    private static final String SEGMENT = "NSEFO";
    private static final int STRIKE_INTERVAL = 50;
    private static final String TRADING_EXPIRY = "currentWeek";
    private static final int MAX_RE_ENTRIES = 5;
    private static final int COOLDOWN_SECONDS = 120;

    // ── ATM rounding to nearest 100 ────────────────────────────────────
    private static final int ATM_ROUNDING = 100;

    // ── PNL-based SL ───────────────────────────────────────────────────
    private static final double PNL_STOP_LOSS = 5000.0;
    private static final double PNL_TARGET = 10000.0;

    // ── Runtime state ──────────────────────────────────────────────────
    private Signal activeSignal;
    private int currentStrike;
    private boolean manualExitRequested;
    private boolean lastExitWasSL;

    public NiftyPhoenixCore(OrderService orderService, MarketDataProvider marketData) {
        super(orderService, marketData, StrategyConfig.builder()
                .underlying(UNDERLYING)
                .segment(SEGMENT)
                .strikeInterval(STRIKE_INTERVAL)
                .tradingExpiry(TRADING_EXPIRY)
                .maxReEntries(MAX_RE_ENTRIES)
                .exitCooldownSeconds(COOLDOWN_SECONDS)
                .defaultStopLoss(PNL_STOP_LOSS)
                .build());
    }

    @Override
    protected void onEntry() {
        double spot = marketData.getSpotPrice(UNDERLYING);

        // Round to nearest 100 for stability
        int atmStrike = roundToNearest(spot, ATM_ROUNDING);

        String ceSymbol = marketData.resolveSymbol(UNDERLYING, atmStrike, "CE", TRADING_EXPIRY);
        String peSymbol = marketData.resolveSymbol(UNDERLYING, atmStrike, "PE", TRADING_EXPIRY);
        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) return;
        if (ceQuote.getLtp() <= 0 || peQuote.getLtp() <= 0) return;

        StrategyLeg ceLeg = StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(atmStrike).quantity(1)
                .entryPrice(ceQuote.getLtp()).currentPrice(ceQuote.getLtp())
                .status("OPEN").build();

        StrategyLeg peLeg = StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(atmStrike).quantity(1)
                .entryPrice(peQuote.getLtp()).currentPrice(peQuote.getLtp())
                .status("OPEN").build();

        activeSignal = Signal.builder()
                .strategyId(0L).status("LIVE")
                .currentAtm(atmStrike).baseIndexPrice(spot)
                .addLeg(ceLeg).addLeg(peLeg)
                .build();

        orderService.placeEntryOrders(activeSignal);
        currentStrike = atmStrike;
        manualExitRequested = false;
        lastExitWasSL = false;
        markEntry();
        logger.info("[Phoenix] Entry at strike=" + atmStrike
                + " cePremium=" + ceQuote.getLtp()
                + " pePremium=" + peQuote.getLtp());
    }

    @Override
    protected boolean shouldExit() {
        if (activeSignal == null) return false;
        if (manualExitRequested) return true;

        // Refresh LTPs and compute PnL
        double pnl = computeLivePnl();

        // PNL stop-loss
        if (pnl < -PNL_STOP_LOSS) {
            lastExitWasSL = true;
            logger.info("[Phoenix] SL triggered, pnl=" + pnl);
            return true;
        }

        // Profit target
        if (pnl >= PNL_TARGET) {
            lastExitWasSL = false;
            logger.info("[Phoenix] Target hit, pnl=" + pnl);
            return true;
        }

        // Time-based
        if (!marketData.isMarketOpen()) {
            lastExitWasSL = false;
            return true;
        }

        return false;
    }

    @Override
    protected void onExit() {
        if (activeSignal != null) {
            orderService.placeExitOrders(activeSignal);
            activeSignal = null;
        }
        markExit();
        logger.info("[Phoenix] Exited strike=" + currentStrike
                + " wasSL=" + lastExitWasSL
                + " reEntries=" + reEntryCount + "/" + MAX_RE_ENTRIES);
    }

    @Override
    protected void onExitEvaluation() {
        // Live PnL monitoring is done in shouldExit()
    }

    public void requestManualExit() {
        this.manualExitRequested = true;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private int roundToNearest(double price, int rounding) {
        return (int) (Math.round(price / rounding) * rounding);
    }

    private double computeLivePnl() {
        if (activeSignal == null) return 0.0;
        double pnl = 0.0;
        for (StrategyLeg leg : activeSignal.getLegs()) {
            OptionQuote q = marketData.getOptionQuote(leg.getName());
            if (q != null) {
                double diff = leg.getEntryPrice() - q.getLtp(); // SELL profit
                pnl += diff * leg.getQuantity();
            }
        }
        return pnl;
    }
}
