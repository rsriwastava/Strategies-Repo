package com.quantlab.strategies.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Broker-agnostic signal representing a trading decision and its associated legs.
 * <p>
 * A signal transitions through the following lifecycle:
 * <pre>
 *   PENDING -> LIVE -> EXIT
 * </pre>
 * Each signal owns zero or more {@link StrategyLeg} instances that define
 * the individual option positions comprising the trade.
 * <p>
 * Construct via the {@link Builder}.
 */
public final class Signal {

    private final Long id;
    private final Long strategyId;
    private final String status;            // PENDING, LIVE, EXIT
    private final List<StrategyLeg> legs;
    private final Instant createdAt;
    private final int currentAtm;
    private final double baseIndexPrice;

    private Signal(Builder builder) {
        this.id = builder.id;
        this.strategyId = Objects.requireNonNull(builder.strategyId, "strategyId must not be null");
        this.status = Objects.requireNonNull(builder.status, "status must not be null");
        this.legs = Collections.unmodifiableList(new ArrayList<>(builder.legs));
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.currentAtm = builder.currentAtm;
        this.baseIndexPrice = builder.baseIndexPrice;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public Long getId() { return id; }

    public Long getStrategyId() { return strategyId; }

    public String getStatus() { return status; }

    /** Returns an unmodifiable view of the legs. */
    public List<StrategyLeg> getLegs() { return legs; }

    public Instant getCreatedAt() { return createdAt; }

    public int getCurrentAtm() { return currentAtm; }

    public double getBaseIndexPrice() { return baseIndexPrice; }

    // ── Derived helpers ──────────────────────────────────────────────────

    public boolean isLive() { return "LIVE".equals(status); }

    public boolean isPending() { return "PENDING".equals(status); }

    /**
     * Aggregate unrealised PnL across all open legs.
     */
    public double getTotalUnrealisedPnl() {
        return legs.stream()
                .filter(StrategyLeg::isOpen)
                .mapToDouble(StrategyLeg::getUnrealisedPnl)
                .sum();
    }

    /**
     * Returns the count of legs that are still in OPEN status.
     */
    public int getOpenLegCount() {
        return (int) legs.stream().filter(StrategyLeg::isOpen).count();
    }

    // ── Object overrides ─────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Signal)) return false;
        Signal signal = (Signal) o;
        return Objects.equals(id, signal.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Signal{"
                + "id=" + id
                + ", strategyId=" + strategyId
                + ", status='" + status + '\''
                + ", legs=" + legs.size()
                + ", atm=" + currentAtm
                + ", basePrice=" + baseIndexPrice
                + ", createdAt=" + createdAt
                + '}';
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long id;
        private Long strategyId;
        private String status = "PENDING";
        private List<StrategyLeg> legs = new ArrayList<>();
        private Instant createdAt;
        private int currentAtm;
        private double baseIndexPrice;

        private Builder() {}

        public Builder id(Long id) { this.id = id; return this; }
        public Builder strategyId(Long strategyId) { this.strategyId = strategyId; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder legs(List<StrategyLeg> legs) { this.legs = new ArrayList<>(legs); return this; }
        public Builder addLeg(StrategyLeg leg) { this.legs.add(leg); return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder currentAtm(int currentAtm) { this.currentAtm = currentAtm; return this; }
        public Builder baseIndexPrice(double baseIndexPrice) { this.baseIndexPrice = baseIndexPrice; return this; }

        public Signal build() {
            return new Signal(this);
        }
    }
}
