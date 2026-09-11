package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 目標交易日價格的 provenance 標示不能把舊、壞或未來日期誤標為 target。 */
@ExtendWith(MockitoExtension.class)
class StockPriceProvenanceTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 8, 13);

    @Mock private AssetSnapshotRepository snapshots;
    @Mock private ExchangeRateHistoryRepository rates;
    @Mock private StockRepository stocks;
    @Mock private PriceQueryService prices;
    @Mock private MarketDataService markets;

    @Test void targetDatePriceIsTargetSession() { assertThat(provenance("2026-08-13", true)).isEqualTo("TARGET_SESSION_PRICE"); }
    @Test void olderDatePriceIsPreviousSession() { assertThat(provenance("2026-08-12", true)).isEqualTo("PREVIOUS_SESSION_PRICE"); }
    @Test void malformedDatePriceIsUnverified() { assertThat(provenance("not-a-date", true)).isEqualTo("UNVERIFIED_SESSION_PRICE"); }
    @Test void futureDatePriceIsUnverified() { assertThat(provenance("2026-08-14", true)).isEqualTo("UNVERIFIED_SESSION_PRICE"); }
    @Test void missingPriceUsesSnapshotValue() { assertThat(provenance(null, false)).isEqualTo("SNAPSHOT_VALUE"); }

    @Test
    void marketStatusPublishesEachDisplaySessionTargetDate() {
        when(prices.displaySession("台股")).thenReturn(session(LocalDate.of(2026, 8, 13)));
        when(prices.displaySession("美股")).thenReturn(session(LocalDate.of(2026, 8, 12)));
        when(prices.displaySession("英股")).thenReturn(session(LocalDate.of(2026, 8, 13)));

        Map<String, Object> status = service().getMarketStatus();

        assertThat(status).containsEntry("twTradingDate", "2026-08-13")
                .containsEntry("usTradingDate", "2026-08-12")
                .containsEntry("ukTradingDate", "2026-08-13");
    }

    private String provenance(String tradingDate, boolean hasPrice) {
        StockHolding holding = StockHolding.builder().id(7L).stockCode("2330").market("台股")
                .shares(BigDecimal.TEN).currentValue(BigDecimal.valueOf(999)).build();
        AssetSnapshot snapshot = AssetSnapshot.builder().id(8L).snapshotDate(TARGET).usdExchangeRate(BigDecimal.ONE)
                .stocks(List.of(holding)).build();
        when(snapshots.findLatestWithStocks()).thenReturn(Optional.of(snapshot));
        when(prices.displaySession("台股")).thenReturn(new PriceQueryService.DisplaySession(
                PriceQueryService.DisplayPhase.OPEN, TARGET, TARGET));
        when(prices.getDisplayPrice("2330", "台股")).thenReturn(hasPrice
                ? Optional.of(livePrice(tradingDate)) : Optional.empty());
        when(stocks.findAllByCodeIn(Set.of("2330"))).thenReturn(List.of());

        return service().getLiveAssets().stocks().getFirst().valuationSource();
    }

    @Test
    void liveAssetsUsesOneBoundedMasterReadAndKeepsEachQuoteTimestamp() {
        StockHolding tw = StockHolding.builder().id(7L).stockCode("2330").market("台股")
                .shares(BigDecimal.ONE).build();
        StockHolding us = StockHolding.builder().id(8L).stockCode("AAPL").market("美股")
                .shares(BigDecimal.ONE).build();
        AssetSnapshot snapshot = AssetSnapshot.builder().id(9L).snapshotDate(TARGET)
                .usdExchangeRate(BigDecimal.ONE).stocks(List.of(tw, us)).build();
        when(snapshots.findLatestWithStocks()).thenReturn(Optional.of(snapshot));
        when(prices.displaySession("台股")).thenReturn(session(TARGET));
        when(prices.displaySession("美股")).thenReturn(session(TARGET));
        when(prices.getDisplayPrice("2330", "台股")).thenReturn(Optional.of(
                livePrice("2330", "台股", "2026-08-13", "2026-08-13T10:01:00")));
        when(prices.getDisplayPrice("AAPL", "美股")).thenReturn(Optional.of(
                livePrice("AAPL", "美股", "2026-08-13", "2026-08-13T10:05:00-04:00")));
        when(stocks.findAllByCodeIn(Set.of("2330", "AAPL"))).thenReturn(List.of(
                Stock.builder().code("2330").market("台股").name("台積電").build(),
                Stock.builder().code("AAPL").market("美股").name("Apple").build()));

        StockPriceService.LiveAssetsResponse response = service().getLiveAssets();

        assertThat(response.stocks()).extracting(StockPriceService.LiveStockItem::stockName)
                .containsExactly("台積電", "Apple");
        assertThat(response.stocks()).extracting(StockPriceService.LiveStockItem::updatedAt)
                .containsExactly(Instant.parse("2026-08-13T02:01:00Z"), Instant.parse("2026-08-13T14:05:00Z"));
        assertThat(response.priceUpdatedAt()).isEqualTo("2026-08-13T14:05:00Z");
        verify(stocks).findAllByCodeIn(Set.of("2330", "AAPL"));
        verify(stocks, never()).findByCodeAndMarket(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void malformedLiveTimestampFailsSoftInsteadOfBreakingLiveAssetsRead() {
        StockHolding holding = StockHolding.builder().stockCode("2330").market("台股")
                .shares(BigDecimal.ONE).build();
        AssetSnapshot snapshot = AssetSnapshot.builder().id(9L).snapshotDate(TARGET)
                .usdExchangeRate(BigDecimal.ONE).stocks(List.of(holding)).build();
        when(snapshots.findLatestWithStocks()).thenReturn(Optional.of(snapshot));
        when(prices.displaySession("台股")).thenReturn(session(TARGET));
        when(prices.getDisplayPrice("2330", "台股")).thenReturn(Optional.of(
                livePrice("2330", "台股", "2026-08-13", "not-a-time")));
        when(stocks.findAllByCodeIn(Set.of("2330"))).thenReturn(List.of());

        StockPriceService.LiveAssetsResponse response = service().getLiveAssets();

        assertThat(response.stocks().getFirst().updatedAt()).isNull();
        assertThat(response.priceUpdatedAt()).isNull();
    }

    @Test
    void allPricesUsesOneBoundedMasterReadWithoutPerPriceLookup() {
        StockHolding tw = StockHolding.builder().stockCode("2330").market("台股").build();
        StockHolding us = StockHolding.builder().stockCode("AAPL").market("美股").build();
        AssetSnapshot snapshot = AssetSnapshot.builder().stocks(List.of(tw, us)).build();
        when(snapshots.findLatestWithStocks()).thenReturn(Optional.of(snapshot));
        when(prices.getAllDisplayPrices(Set.of(
                new PriceQueryService.PriceKey("2330", "台股"),
                new PriceQueryService.PriceKey("AAPL", "美股"))))
                .thenReturn(List.of(
                        livePrice("2330", "台股", "2026-08-13", "2026-08-13T10:01:00"),
                        livePrice("AAPL", "美股", "2026-08-13", "2026-08-13T10:05:00")));
        when(stocks.findAllByCodeIn(Set.of("2330", "AAPL"))).thenReturn(List.of(
                Stock.builder().code("2330").market("台股").name("台積電").build(),
                Stock.builder().code("AAPL").market("美股").name("Apple").build()));

        List<StockPriceService.StockPriceDto> result = service().getAllPrices();

        assertThat(result).extracting(StockPriceService.StockPriceDto::stockName)
                .containsExactly("台積電", "Apple");
        verify(stocks).findAllByCodeIn(Set.of("2330", "AAPL"));
        verify(stocks, never()).findByCodeAndMarket(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private StockPriceService service() {
        return new StockPriceService(snapshots, rates, stocks, prices, markets);
    }

    private static PriceQueryService.DisplaySession session(LocalDate target) {
        return new PriceQueryService.DisplaySession(PriceQueryService.DisplayPhase.PREVIOUS_SESSION, target, TARGET);
    }

    private static PriceQueryService.LivePrice livePrice(String tradingDate) {
        return livePrice("2330", "台股", tradingDate, null);
    }

    private static PriceQueryService.LivePrice livePrice(String code, String market, String tradingDate, String updatedAt) {
        return new PriceQueryService.LivePrice(code, "台積電", market, BigDecimal.valueOf(100),
                null, null, null, null, null, null, null, null, null, tradingDate,
                updatedAt, false, "TEST", "LIVE");
    }
}
