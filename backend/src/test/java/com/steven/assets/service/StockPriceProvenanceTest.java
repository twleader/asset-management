package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(stocks.findByCodeAndMarket("2330", "台股")).thenReturn(Optional.empty());

        return service().getLiveAssets().stocks().getFirst().valuationSource();
    }

    private StockPriceService service() {
        return new StockPriceService(snapshots, rates, stocks, prices, markets);
    }

    private static PriceQueryService.DisplaySession session(LocalDate target) {
        return new PriceQueryService.DisplaySession(PriceQueryService.DisplayPhase.PREVIOUS_SESSION, target, TARGET);
    }

    private static PriceQueryService.LivePrice livePrice(String tradingDate) {
        return new PriceQueryService.LivePrice("2330", "台積電", "台股", BigDecimal.valueOf(100),
                null, null, null, null, null, null, null, null, null, tradingDate,
                null, false, "TEST", "LIVE");
    }
}
