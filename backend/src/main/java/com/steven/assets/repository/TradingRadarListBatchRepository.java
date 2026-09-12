package com.steven.assets.repository;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.model.EtfNavObservation;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read-only, exact-natural-key persistence boundary for one Radar list request.
 *
 * <p>The service layer deliberately receives only immutable results from this port.  In
 * particular, callers must not turn an absent key into a scalar repository lookup.</p>
 */
public interface TradingRadarListBatchRepository {

    record Key(String code, String market) {}

    Map<Key, Stock> findStocks(Collection<Key> keys);

    Map<Key, List<StockPriceHistory>> findRecentPrices(Collection<Key> keys, int limit);

    Map<Key, List<StockPriceHistory>> findAllPrices(Collection<Key> keys);

    Map<Key, List<EtfNavObservation>> findEtfNavObservations(Collection<Key> keys, Instant decisionInstant);

    Map<Key, List<StockDividendHistory>> findAdjustmentEvents(
            Collection<Key> keys, Map<Key, List<StockPriceHistory>> pricesByKey);

    /** One shared as-of curve stream for every bond row in the request. */
    List<TreasuryYieldDto.StoredBatch> findCompleteTreasurySeriesThrough(Instant decisionInstant);

    /** One persisted currency stream; callers derive as-of changes in memory. */
    List<ExchangeRateHistory> findExchangeRatesByCurrency(String currency);
}
