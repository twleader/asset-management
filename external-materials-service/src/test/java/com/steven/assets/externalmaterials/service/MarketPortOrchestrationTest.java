package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.model.marketdata.NormalizedQuoteData;
import com.steven.assets.externalmaterials.model.marketdata.OrderBookCacheData;
import com.steven.assets.externalmaterials.model.marketdata.OrderBookData;
import com.steven.assets.externalmaterials.model.marketdata.PriceData.PriceResult;
import com.steven.assets.externalmaterials.model.marketdata.PriceProjectionData;
import com.steven.assets.externalmaterials.model.marketdata.ProviderTimedPriceObservation;
import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData;
import com.steven.assets.externalmaterials.model.marketdata.StockMarketData;
import com.steven.assets.externalmaterials.service.port.FubonNormalizedQuotePort;
import com.steven.assets.externalmaterials.service.port.LivePriceFetchPort;
import com.steven.assets.externalmaterials.service.port.OrderBookCachePort;
import com.steven.assets.externalmaterials.service.port.OrderBookRepository;
import com.steven.assets.externalmaterials.service.port.PriceProjectionPort;
import com.steven.assets.externalmaterials.service.port.StockMarketRepository;
import com.steven.assets.externalmaterials.service.port.StockUniversePort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Core orchestration runs against contracts only; no HTTP, JDBC or Redis adapter is created. */
class MarketPortOrchestrationTest {
    @Test
    void normalizedObservationIsPersistedBeforeOnlyTheCommittedCanonicalValueIsProjected() {
        List<String> operations = new ArrayList<>();
        PriceResult raw = quote("100");
        PriceResult committed = quote("101");
        StockMarketRepository repository = new StockMarketRepository() {
            @Override public StockMarketData.IntradayPersistenceResult persistIntradayQuote(PriceResult value) {
                assertThat(value).isEqualTo(raw);
                operations.add("db-committed");
                return StockMarketData.IntradayPersistenceResult.applied(new StockMarketData.IntradayQuote(
                        committed.stockCode(), committed.market(), committed.tradingDate(), committed.freshnessInstant(),
                        committed.source(), committed.price(), committed.previousClose(), committed.openPrice(),
                        committed.highPrice(), committed.lowPrice(), committed.buyPrice(), committed.sellPrice(),
                        committed.volume(), committed.stockName()));
            }
            @Override public Optional<StockMarketData.IntradayQuote> findIntradayQuote(String code, String market) { throw new AssertionError(); }
            @Override public Optional<StockMarketData.DatedClose> findLatestDatedClose(String code, String market) { throw new AssertionError(); }
            @Override public List<StockMarketData.ClosePoint> loadRecentTaiexCloses(int count) { throw new AssertionError(); }
        };
        StockUniversePort universe = new StockUniversePort() {
            @Override public void collectTwRadarCodes(Set<String> codes) { codes.add("2330"); }
            @Override public void collectHeldStockCodes(Set<String> tw, Set<String> us, Set<String> uk) { throw new AssertionError(); }
            @Override public void collectAllStockCodes(Set<String> tw, Set<String> us, Set<String> uk) { throw new AssertionError(); }
        };
        PriceProjectionPort projection = mock(PriceProjectionPort.class);
        when(projection.writeTaiwanLive(committed, true)).thenAnswer(call -> {
            assertThat(operations).containsExactly("db-committed");
            operations.add("redis-canonical");
            return PriceProjectionData.CacheWriteOutcome.WRITTEN;
        });
        FubonNormalizedQuotePort fubon = codes -> {
            assertThat(codes).containsExactly("2330");
            return new NormalizedQuoteData.BatchResult(NormalizedQuoteData.BatchStatus.SUCCESS,
                    List.of(new ProviderTimedPriceObservation(raw, raw.tradingDate(), raw.freshnessInstant())), 1, 0);
        };
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("normalizedQuotes", fubon);
        LivePriceFetchPort fallback = mock(LivePriceFetchPort.class);
        MarketClock clock = mock(MarketClock.class);
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        var dispatcher = new TwLiveQuoteDispatcher(clock, fallback,
                beans.getBeanProvider(FubonNormalizedQuotePort.class), repository, universe, projection,
                new TwLiveQuoteOutcomeCounters(), true, true);

        var result = dispatcher.refresh(Set.of("2330", "0000", "2317"));

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.written()).isEqualTo(1);
        assertThat(operations).containsExactly("db-committed", "redis-canonical");
        verify(projection).writeTaiwanLive(committed, true);
        verifyNoInteractions(fallback);
    }

    @Test
    void readerDoesNotServeCacheWhenCanonicalRevisionCannotBeVerified() {
        List<String> operations = new ArrayList<>();
        OrderBookCachePort cache = new OrderBookCachePort() {
            @Override public Optional<OrderBookCacheData.CachedSnapshot> find(String code, String market) {
                operations.add("cache-candidate");
                return Optional.of(new OrderBookCacheData.CachedSnapshot("7", null));
            }
            @Override public OrderBookCacheData.WriteOutcome writeStrictNewer(OrderBookData.CanonicalSnapshot value) { throw new AssertionError(); }
        };
        OrderBookRepository repository = new OrderBookRepository() {
            @Override public OrderBookData.PersistResult persist(QuoteDetailData.QuoteDetailResult value) { throw new AssertionError(); }
            @Override public OrderBookData.RevisionLookup findRevision(String code, String market) {
                operations.add("db-unavailable");
                return OrderBookData.RevisionLookup.failed();
            }
            @Override public OrderBookData.CanonicalLookup findCanonical(String code, String market) { throw new AssertionError(); }
        };

        var result = new QuoteDetailReadService(cache, repository).read("2330", "台股");

        assertThat(result.available()).isFalse();
        assertThat(result.source()).isNull();
        assertThat(operations).containsExactly("cache-candidate", "db-unavailable");
    }

    private static PriceResult quote(String value) {
        return new PriceResult("2330", "台股", new BigDecimal(value), null, null, "FUBON_INTRADAY", "台積電",
                null, null, new BigDecimal("99"), new BigDecimal("98"), new BigDecimal("102"),
                new BigDecimal("97"), 1L, LocalDate.of(2026, 10, 23), Instant.parse("2026-10-23T03:00:00Z"));
    }
}
