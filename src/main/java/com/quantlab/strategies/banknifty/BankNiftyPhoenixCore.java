package com.quantlab.strategies.banknifty;

import com.quantlab.strategies.core.BaseStrategy;
import com.quantlab.strategies.core.MarketDataProvider;
import com.quantlab.strategies.core.OptionQuote;
import com.quantlab.strategies.core.OrderService;
import com.quantlab.strategies.core.Signal;
import com.quantlab.strategies.core.StrategyConfig;
import com.quantlab.strategies.core.StrategyLeg;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * BANKNIFTY Phoenix ATM Straddle Strategy.
 * <p>
 * Sells an ATM straddle and monitors for stop-loss hits on individual
 * legs. When a leg's loss exceeds the per-leg SL threshold, the losing
 * leg is exited and immediately re-entered at the new ATM strike —
 * the strategy "rises from the ashes" (Phoenix behaviour).
 * <p>
 * The surviving leg retains its original position unless the aggregate
 * portfolio SL is breached, at which point the entire structure is
 * unwound.
 * <p>
 * Key parameters:
 * <ul>
 *   <li>Per-leg SL: 40% of entry premium</li>
 *   <li>Portfolio SL: 100% of total collected premium</li>
 *   <li>Max phoenix re-entries per session: 8</li>
 *   <li>Cooldown between phoenix re-entries: 30 s</li>
 * </ul>
 */
public class BankNiftyPhoenixCore extends BaseStrategy {

    private static final Logger log = Logger.getLogger(BankNiftyPhoenixCore.class.getName());

    // ── Index constants ─────────────────────────────────────────────────
    private static final String UNDERLYING = "BANKNIFTY";
    private static final String SEGMENT    = "NSEFO";
    private static final String EXPIRY     = "currentMonth";
    private static final int    STRIKE_INTERVAL = 100;

    // ── Re-entry and cooldown ───────────────────────────────────────────
    private static final int MAX_RE_ENTRIES   = 8;
    private static final int COOLDOWN_SECONDS = 30;

    // ── SL parameters ───────────────────────────────────────────────────
    private static final double PER_LEG_SL_PCT   = 0.40;
    private static final double PORTFOLIO_SL_PCT = 1.00;

    // ── Phoenix re-entry tracking ───────────────────────────────────────
    private static final long PHOENIX_COOLDOWN_MS = 30_000L;

    // ── Runtime state ───────────────────────────────────────────────────
    private Signal activeSignal;
    private double totalPremiumCollected;
    private int phoenixCount;
    private long lastPhoenixTimestamp;

    public BankNiftyPhoenixCore(OrderService orderService, MarketDataProvider marketData) {
        super(StrategyConfig.builder()
                        .underlying(UNDERLYING)
                        .segment(SEGMENT)
                        .strikeInterval(STRIKE_INTERVAL)
                        .tradingExpiry(EXPIRY)
                        .maxReEntries(MAX_RE_ENTRIES)
                        .exitCooldownSeconds(COOLDOWN_SECONDS)
                        .build(),
                orderService, marketData);
    }

    // ── Entry ───────────────────────────────────────────────────────────

    @Override
    protected void onEntry(long strategyId) {
        double spot = marketData.getSpotPrice(UNDERLYING);
        if (Double.isNaN(spot)) return;

        int atmStrike = marketData.getATM(UNDERLYING, spot, EXPIRY);
        String ceSymbol = buildSymbol(atmStrike, "CE");
        String peSymbol = buildSymbol(atmStrike, "PE");

        OptionQuote ceQuote = marketData.getOptionQuote(ceSymbol);
        OptionQuote peQuote = marketData.getOptionQuote(peSymbol);
        if (ceQuote == null || peQuote == null) return;

        totalPremiumCollected = ceQuote.getLtp() + peQuote.getLtp();
        phoenixCount = 0;

        List<StrategyLeg> legs = new ArrayList<>(2);
        legs.add(StrategyLeg.builder()
                .name(ceSymbol).optionType("CE").side("SELL")
                .strike(atmStrike).quantity(1)
                .entryPrice(ceQuote.getLtp()).currentPrice(ceQuote.getLtp())
                .status("OPEN").build());
        legs.add(StrategyLeg.builder()
                .name(peSymbol).optionType("PE").side("SELL")
                .strike(atmStrike).quantity(1)
                .entryPrice(peQuote.getLtp()).currentPrice(peQuote.getLtp())
                .status("OPEN").build());

        activeSignal = Signal.builder()
                .strategyId(strategyId)
                .status("LIVE")
                .currentAtm(atmStrike)
                .baseIndexPrice(spot)
                .legs(legs)
                .build();

        orderService.placeEntryOrders(activeSignal);
        log.info("[" + strategyId + "] PhoenixCore entry: ATM=" + atmStrike
                + " premium=" + totalPremiumCollected);
    }

    // ── Exit evaluation (phoenix re-entry on leg SL) ────────────────────

    @Override
    protected void onExitEvaluation(long strategyId) {
        if (activeSignal == null) return;

        long now = System.currentTimeMillis();
        if (now - lastPhoenixTimestamp < PHOENIX_COOLDOWN_MS) {
            if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
                onExit(strategyId);
            }
            return;
        }

        for (StrategyLeg leg : activeSignal.getLegs()) {
            if (!leg.isOpen()) continue;

            OptionQuote quote = marketData.getOptionQuote(leg.getName());
            if (quote == null) continue;

            double lossPct = (quote.getLtp() - leg.getEntryPrice())
                    / leg.getEntryPrice();

            if (lossPct >= PER_LEG_SL_PCT && phoenixCount < MAX_RE_ENTRIES) {
                log.info("[" + strategyId + "] Phoenix trigger on " + leg.getName()
                        + " loss=" + (lossPct * 100) + "%");

                orderService.placeSingleLegExit(activeSignal, leg);

                double spot = marketData.getSpotPrice(UNDERLYING);
                if (Double.isNaN(spot)) break;

                int newAtm = marketData.getATM(UNDERLYING, spot, EXPIRY);
                String newSymbol = buildSymbol(newAtm, leg.getOptionType());
                OptionQuote newQuote = marketData.getOptionQuote(newSymbol);
                if (newQuote == null) break;

                StrategyLeg newLeg = StrategyLeg.builder()
                        .name(newSymbol)
                        .optionType(leg.getOptionType())
                        .side("SELL")
                        .strike(newAtm)
                        .quantity(1)
                        .entryPrice(newQuote.getLtp())
                        .currentPrice(newQuote.getLtp())
                        .status("OPEN")
                        .build();

                List<StrategyLeg> updatedLegs = new ArrayList<>();
                for (StrategyLeg existing : activeSignal.getLegs()) {
                    if (existing == leg) {
                        updatedLegs.add(newLeg);
                    } else {
                        updatedLegs.add(existing);
                    }
                }

                activeSignal = Signal.builder()
                        .strategyId(strategyId)
                        .status("LIVE")
                        .currentAtm(newAtm)
                        .baseIndexPrice(spot)
                        .legs(updatedLegs)
                        .build();

                Signal reEntrySignal = Signal.builder()
                        .strategyId(strategyId)
                        .status("LIVE")
                        .currentAtm(newAtm)
                        .baseIndexPrice(spot)
                        .addLeg(newLeg)
                        .build();
                orderService.placeEntryOrders(reEntrySignal);

                totalPremiumCollected += newQuote.getLtp();
                phoenixCount++;
                lastPhoenixTimestamp = now;
                log.info("[" + strategyId + "] Phoenix re-entry #" + phoenixCount
                        + " at " + newAtm);
                break;
            }
        }

        if (shouldExit(strategyId, activeSignal.getId() != null ? activeSignal.getId() : 0L)) {
            onExit(strategyId);
        }
    }

    // ── Should exit ─────────────────────────────────────────────────────

    @Override
    protected boolean shouldExit(long strategyId, long signalId) {
        if (activeSignal == null) return false;

        double portfolioPnl = computePortfolioPnl();
        double slLimit = -(totalPremiumCollected * PORTFOLIO_SL_PCT);

        if (portfolioPnl <= slLimit) {
            log.info("[" + strategyId + "] Portfolio SL hit: pnl=" + portfolioPnl);
            return true;
        }
        return false;
    }

    // ── Exit ────────────────────────────────────────────────────────────

    @Override
    protected void onExit(long strategyId) {
        if (activeSignal == null) return;

        orderService.placeExitOrders(activeSignal);
        log.info("[" + strategyId + "] PhoenixCore exit: pnl="
                + computePortfolioPnl() + " phoenixCount=" + phoenixCount);
        activeSignal = null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private double computePortfolioPnl() {
        double pnl = 0;
        for (StrategyLeg leg : activeSignal.getLegs()) {
            if (!leg.isOpen()) continue;
            OptionQuote quote = marketData.getOptionQuote(leg.getName());
            if (quote == null) continue;
            double diff = quote.getLtp() - leg.getEntryPrice();
            pnl += "SELL".equals(leg.getSide())
                    ? -diff * leg.getQuantity()
                    : diff * leg.getQuantity();
        }
        return pnl;
    }

    private String buildSymbol(int strike, String optionType) {
        return UNDERLYING + "-" + EXPIRY + "-" + strike + optionType;
    }
}
