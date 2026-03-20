package com.quantlab.strategies.core;

/**
 * Market data abstraction layer.
 * <p>
 * Decouples strategy logic from the data feed provider. Implementations
 * may source data from in-memory caches, WebSocket feeds, REST APIs,
 * or any combination thereof.
 * <p>
 * <b>Contract:</b>
 * <ul>
 *   <li>All methods must be thread-safe.</li>
 *   <li>Price methods return {@code Double.NaN} if data is unavailable.</li>
 *   <li>{@link #getOptionQuote(String)} returns {@code null} if the
 *       symbol is unknown or has no data.</li>
 *   <li>Callers should check {@link #isDataFresh(String, int)} before
 *       making trading decisions.</li>
 * </ul>
 */
public interface MarketDataProvider {

    /**
     * Returns the latest spot (cash) price for the given underlying.
     *
     * @param underlying index symbol, e.g. "NIFTY", "BANKNIFTY", "SENSEX"
     * @return spot price, or {@code Double.NaN} if unavailable
     */
    double getSpotPrice(String underlying);

    /**
     * Returns the synthetic futures price derived from options
     * (put-call parity) for the given underlying.
     *
     * @param underlying index symbol
     * @return synthetic price, or {@code Double.NaN} if unavailable
     */
    double getSyntheticPrice(String underlying);

    /**
     * Computes the at-the-money (ATM) strike for the given underlying,
     * price, and expiry. The result is rounded to the nearest valid
     * strike interval for that underlying.
     *
     * @param underlying index symbol
     * @param price      reference price (spot or synthetic)
     * @param expiry     expiry identifier, e.g. "2026-03-26" or "currentWeek"
     * @return ATM strike value
     */
    int getATM(String underlying, double price, String expiry);

    /**
     * Returns a full option quote snapshot for the given symbol.
     *
     * @param symbol fully qualified option symbol,
     *               e.g. "NIFTY2026-03-26-24000CE"
     * @return option quote with greeks and market data,
     *         or {@code null} if unavailable
     */
    OptionQuote getOptionQuote(String symbol);

    /**
     * Returns the implied volatility for the given option symbol.
     *
     * @param symbol fully qualified option symbol
     * @return IV as a decimal (e.g. 0.18 for 18%), or {@code Double.NaN}
     */
    double getIV(String symbol);

    /**
     * Returns the delta for the given option symbol.
     *
     * @param symbol fully qualified option symbol
     * @return delta value, or {@code Double.NaN} if unavailable
     */
    double getDelta(String symbol);

    /**
     * Checks whether market data for the given underlying has been
     * updated within the specified staleness window.
     *
     * @param underlying      index symbol
     * @param maxStaleSeconds maximum acceptable age of the last update
     * @return {@code true} if data is fresh, {@code false} otherwise
     */
    boolean isDataFresh(String underlying, int maxStaleSeconds);
}
