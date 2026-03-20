package com.quantlab.strategies.core;

import java.util.Objects;

/**
 * Represents a single leg of a multi-leg option strategy.
 * <p>
 * A leg captures the instrument, direction, quantity, and fill/current
 * prices needed for PnL computation and exit management. Legs are
 * intentionally broker-agnostic — no exchange-specific order IDs or
 * protocol references are stored here.
 * <p>
 * Construct via the {@link Builder}.
 */
public final class StrategyLeg {

    private final Long id;
    private final String name;          // e.g. "NIFTY2026-03-20-24000CE"
    private final String optionType;    // CE, PE
    private final String side;          // BUY, SELL
    private final int strike;
    private final int quantity;
    private final double entryPrice;
    private final double currentPrice;
    private final String status;        // OPEN, CLOSED, PENDING

    private StrategyLeg(Builder builder) {
        this.id = builder.id;
        this.name = Objects.requireNonNull(builder.name, "name must not be null");
        this.optionType = Objects.requireNonNull(builder.optionType, "optionType must not be null");
        this.side = Objects.requireNonNull(builder.side, "side must not be null");
        this.strike = builder.strike;
        this.quantity = builder.quantity;
        this.entryPrice = builder.entryPrice;
        this.currentPrice = builder.currentPrice;
        this.status = Objects.requireNonNull(builder.status, "status must not be null");
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public Long getId() { return id; }

    public String getName() { return name; }

    public String getOptionType() { return optionType; }

    public String getSide() { return side; }

    public int getStrike() { return strike; }

    public int getQuantity() { return quantity; }

    public double getEntryPrice() { return entryPrice; }

    public double getCurrentPrice() { return currentPrice; }

    public String getStatus() { return status; }

    // ── Derived helpers ──────────────────────────────────────────────────

    /**
     * Unrealised PnL for this leg in absolute terms (not per lot).
     * Positive for profitable positions.
     */
    public double getUnrealisedPnl() {
        double diff = currentPrice - entryPrice;
        return "SELL".equals(side) ? -diff * quantity : diff * quantity;
    }

    public boolean isOpen() { return "OPEN".equals(status); }

    public boolean isBuy() { return "BUY".equals(side); }

    public boolean isCall() { return "CE".equals(optionType); }

    // ── Object overrides ─────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StrategyLeg)) return false;
        StrategyLeg that = (StrategyLeg) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "StrategyLeg{"
                + "id=" + id
                + ", name='" + name + '\''
                + ", type=" + optionType
                + ", side=" + side
                + ", strike=" + strike
                + ", qty=" + quantity
                + ", entry=" + entryPrice
                + ", current=" + currentPrice
                + ", status=" + status
                + '}';
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long id;
        private String name;
        private String optionType;
        private String side;
        private int strike;
        private int quantity;
        private double entryPrice;
        private double currentPrice;
        private String status = "PENDING";

        private Builder() {}

        public Builder id(Long id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder optionType(String optionType) { this.optionType = optionType; return this; }
        public Builder side(String side) { this.side = side; return this; }
        public Builder strike(int strike) { this.strike = strike; return this; }
        public Builder quantity(int quantity) { this.quantity = quantity; return this; }
        public Builder entryPrice(double entryPrice) { this.entryPrice = entryPrice; return this; }
        public Builder currentPrice(double currentPrice) { this.currentPrice = currentPrice; return this; }
        public Builder status(String status) { this.status = status; return this; }

        public StrategyLeg build() {
            return new StrategyLeg(this);
        }
    }
}
