package com.quantlab.strategies.core;

import java.util.Objects;

/**
 * Immutable snapshot of an option contract's market data at a point in time.
 * <p>
 * Includes price fields (bid, ask, LTP), Greeks (delta, gamma, theta, vega),
 * implied volatility, volume, open interest, and a staleness timestamp.
 * <p>
 * Construct via the {@link Builder}.
 */
public final class OptionQuote {

    private final double bid;
    private final double ask;
    private final double ltp;
    private final double iv;
    private final double delta;
    private final double gamma;
    private final double theta;
    private final double vega;
    private final long volume;
    private final long openInterest;
    private final long lastUpdateTimestamp;

    private OptionQuote(Builder builder) {
        this.bid = builder.bid;
        this.ask = builder.ask;
        this.ltp = builder.ltp;
        this.iv = builder.iv;
        this.delta = builder.delta;
        this.gamma = builder.gamma;
        this.theta = builder.theta;
        this.vega = builder.vega;
        this.volume = builder.volume;
        this.openInterest = builder.openInterest;
        this.lastUpdateTimestamp = builder.lastUpdateTimestamp;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public double getBid() { return bid; }

    public double getAsk() { return ask; }

    public double getLtp() { return ltp; }

    /** Mid-price computed as the average of bid and ask. */
    public double getMid() { return (bid + ask) / 2.0; }

    /** Bid-ask spread in absolute terms. */
    public double getSpread() { return ask - bid; }

    public double getIv() { return iv; }

    public double getDelta() { return delta; }

    public double getGamma() { return gamma; }

    public double getTheta() { return theta; }

    public double getVega() { return vega; }

    public long getVolume() { return volume; }

    public long getOpenInterest() { return openInterest; }

    /** Epoch milliseconds of the last market data update from the feed. */
    public long getLastUpdateTimestamp() { return lastUpdateTimestamp; }

    // ── Object overrides ─────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OptionQuote)) return false;
        OptionQuote that = (OptionQuote) o;
        return Double.compare(that.bid, bid) == 0
                && Double.compare(that.ask, ask) == 0
                && Double.compare(that.ltp, ltp) == 0
                && Double.compare(that.iv, iv) == 0
                && Double.compare(that.delta, delta) == 0
                && Double.compare(that.gamma, gamma) == 0
                && Double.compare(that.theta, theta) == 0
                && Double.compare(that.vega, vega) == 0
                && volume == that.volume
                && openInterest == that.openInterest
                && lastUpdateTimestamp == that.lastUpdateTimestamp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bid, ask, ltp, iv, delta, gamma, theta, vega,
                volume, openInterest, lastUpdateTimestamp);
    }

    @Override
    public String toString() {
        return "OptionQuote{"
                + "bid=" + bid
                + ", ask=" + ask
                + ", ltp=" + ltp
                + ", iv=" + iv
                + ", delta=" + delta
                + ", gamma=" + gamma
                + ", theta=" + theta
                + ", vega=" + vega
                + ", volume=" + volume
                + ", oi=" + openInterest
                + ", ts=" + lastUpdateTimestamp
                + '}';
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private double bid;
        private double ask;
        private double ltp;
        private double iv;
        private double delta;
        private double gamma;
        private double theta;
        private double vega;
        private long volume;
        private long openInterest;
        private long lastUpdateTimestamp;

        private Builder() {}

        public Builder bid(double bid) { this.bid = bid; return this; }
        public Builder ask(double ask) { this.ask = ask; return this; }
        public Builder ltp(double ltp) { this.ltp = ltp; return this; }
        public Builder iv(double iv) { this.iv = iv; return this; }
        public Builder delta(double delta) { this.delta = delta; return this; }
        public Builder gamma(double gamma) { this.gamma = gamma; return this; }
        public Builder theta(double theta) { this.theta = theta; return this; }
        public Builder vega(double vega) { this.vega = vega; return this; }
        public Builder volume(long volume) { this.volume = volume; return this; }
        public Builder openInterest(long openInterest) { this.openInterest = openInterest; return this; }
        public Builder lastUpdateTimestamp(long lastUpdateTimestamp) { this.lastUpdateTimestamp = lastUpdateTimestamp; return this; }

        public OptionQuote build() {
            return new OptionQuote(this);
        }
    }
}
