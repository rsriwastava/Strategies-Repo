package com.quantlab.strategies.core;

import java.util.Objects;

/**
 * Immutable configuration for a strategy instance.
 * <p>
 * Each index variant (NIFTY / BANKNIFTY / SENSEX) creates its own config.
 * Once built, the configuration cannot be modified — all fields are
 * {@code final} and no setters are exposed.
 * <p>
 * Construct via the {@link Builder}.
 *
 * <pre>{@code
 * StrategyConfig cfg = StrategyConfig.builder()
 *         .underlying("BANKNIFTY")
 *         .exchange("NSE")
 *         .segment("NSEFO")
 *         .strikeInterval(100)
 *         .tradingExpiry("currentWeek")
 *         .maxReEntries(3)
 *         .exitCooldownSeconds(120)
 *         .defaultStopLoss(1.5)
 *         .build();
 * }</pre>
 */
public final class StrategyConfig {

    private final String underlying;          // "NIFTY", "BANKNIFTY", "SENSEX"
    private final String exchange;            // "NSE", "BSE"
    private final String segment;             // "NSEFO", "BSEFO"
    private final int strikeInterval;         // 50, 100, 200
    private final String tradingExpiry;       // "currentWeek", "nextWeek", "currentMonth"
    private final int maxReEntries;
    private final int exitCooldownSeconds;
    private final double defaultStopLoss;

    private StrategyConfig(Builder builder) {
        this.underlying = Objects.requireNonNull(builder.underlying, "underlying must not be null");
        this.exchange = Objects.requireNonNull(builder.exchange, "exchange must not be null");
        this.segment = Objects.requireNonNull(builder.segment, "segment must not be null");
        this.strikeInterval = builder.strikeInterval;
        this.tradingExpiry = Objects.requireNonNull(builder.tradingExpiry, "tradingExpiry must not be null");
        this.maxReEntries = builder.maxReEntries;
        this.exitCooldownSeconds = builder.exitCooldownSeconds;
        this.defaultStopLoss = builder.defaultStopLoss;

        if (strikeInterval <= 0) {
            throw new IllegalArgumentException("strikeInterval must be positive, got: " + strikeInterval);
        }
        if (maxReEntries < 0) {
            throw new IllegalArgumentException("maxReEntries must be non-negative, got: " + maxReEntries);
        }
        if (exitCooldownSeconds < 0) {
            throw new IllegalArgumentException("exitCooldownSeconds must be non-negative, got: " + exitCooldownSeconds);
        }
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getUnderlying() { return underlying; }

    public String getExchange() { return exchange; }

    public String getSegment() { return segment; }

    public int getStrikeInterval() { return strikeInterval; }

    public String getTradingExpiry() { return tradingExpiry; }

    public int getMaxReEntries() { return maxReEntries; }

    public int getExitCooldownSeconds() { return exitCooldownSeconds; }

    public double getDefaultStopLoss() { return defaultStopLoss; }

    // ── Object overrides ─────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StrategyConfig)) return false;
        StrategyConfig that = (StrategyConfig) o;
        return strikeInterval == that.strikeInterval
                && maxReEntries == that.maxReEntries
                && exitCooldownSeconds == that.exitCooldownSeconds
                && Double.compare(that.defaultStopLoss, defaultStopLoss) == 0
                && underlying.equals(that.underlying)
                && exchange.equals(that.exchange)
                && segment.equals(that.segment)
                && tradingExpiry.equals(that.tradingExpiry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(underlying, exchange, segment, strikeInterval,
                tradingExpiry, maxReEntries, exitCooldownSeconds, defaultStopLoss);
    }

    @Override
    public String toString() {
        return "StrategyConfig{"
                + "underlying='" + underlying + '\''
                + ", exchange='" + exchange + '\''
                + ", segment='" + segment + '\''
                + ", strikeInterval=" + strikeInterval
                + ", tradingExpiry='" + tradingExpiry + '\''
                + ", maxReEntries=" + maxReEntries
                + ", exitCooldownSeconds=" + exitCooldownSeconds
                + ", defaultStopLoss=" + defaultStopLoss
                + '}';
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String underlying = "NIFTY";
        private String exchange = "NSE";
        private String segment = "NSEFO";
        private int strikeInterval = 50;
        private String tradingExpiry = "currentWeek";
        private int maxReEntries = 3;
        private int exitCooldownSeconds = 60;
        private double defaultStopLoss = 0.0;

        private Builder() {}

        public Builder underlying(String underlying) { this.underlying = underlying; return this; }
        public Builder exchange(String exchange) { this.exchange = exchange; return this; }
        public Builder segment(String segment) { this.segment = segment; return this; }
        public Builder strikeInterval(int strikeInterval) { this.strikeInterval = strikeInterval; return this; }
        public Builder tradingExpiry(String tradingExpiry) { this.tradingExpiry = tradingExpiry; return this; }
        public Builder maxReEntries(int maxReEntries) { this.maxReEntries = maxReEntries; return this; }
        public Builder exitCooldownSeconds(int exitCooldownSeconds) { this.exitCooldownSeconds = exitCooldownSeconds; return this; }
        public Builder defaultStopLoss(double defaultStopLoss) { this.defaultStopLoss = defaultStopLoss; return this; }

        public StrategyConfig build() {
            return new StrategyConfig(this);
        }
    }
}
