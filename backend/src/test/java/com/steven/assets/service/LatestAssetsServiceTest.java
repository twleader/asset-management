package com.steven.assets.service;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 最新資產聚合的同快照與 target-price-complete fail-closed 邊界。 */
@ExtendWith(MockitoExtension.class)
class LatestAssetsServiceTest {

    @Mock private AssetSnapshotRepository snapshots;
    @Mock private AssetService assets;
    @Mock private StockPriceService prices;

    @Test
    void noSnapshotFailsWithTypedNotFoundSource() {
        when(snapshots.findLatestWithStocks()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getLatest())
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("尚無資產快照");
        verify(assets, never()).getSnapshotDetail(org.mockito.ArgumentMatchers.anyLong());
        verify(prices, never()).getLiveAssets();
    }

    @Test
    void returnsCompleteSnapshotLiveAssetsMarketDatesAndFixedPolicy() {
        AssetSnapshotDto.SnapshotDetailResponse detail = mock(AssetSnapshotDto.SnapshotDetailResponse.class);
        StockPriceService.LiveAssetsResponse live = live(10L, "TARGET_SESSION_PRICE");
        Map<String, Object> marketStatus = Map.of(
                "twTradingDate", "2026-08-13",
                "usTradingDate", "2026-08-13",
                "ukTradingDate", "2026-08-13");
        when(snapshots.findLatestWithStocks()).thenReturn(Optional.of(snapshot(10L)));
        when(assets.getSnapshotDetail(10L)).thenReturn(detail);
        when(prices.getLiveAssets()).thenReturn(live);
        when(prices.getMarketStatus()).thenReturn(marketStatus);

        var response = service().getLatest();

        assertThat(response.generatedAt()).isNotNull();
        assertThat(response.valuationPolicy()).isEqualTo("TARGET_SESSION_WITH_EXPLICIT_FALLBACK");
        assertThat(response.targetPriceComplete()).isTrue();
        assertThat(response.snapshot()).isSameAs(detail);
        assertThat(response.liveAssets()).isSameAs(live);
        assertThat(response.marketStatus()).isSameAs(marketStatus);
    }

    @Test
    void rejectsLiveAssetsFromDifferentSnapshot() {
        when(snapshots.findLatestWithStocks()).thenReturn(Optional.of(snapshot(10L)));
        when(prices.getLiveAssets()).thenReturn(live(11L, "TARGET_SESSION_PRICE"));

        assertThatThrownBy(() -> service().getLatest())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void targetPriceCompleteRequiresEveryHoldingToBeTargetSession() {
        when(snapshots.findLatestWithStocks()).thenReturn(Optional.of(snapshot(10L)));
        when(prices.getLiveAssets()).thenReturn(live(10L, "TARGET_SESSION_PRICE", "PREVIOUS_SESSION_PRICE"));
        when(prices.getMarketStatus()).thenReturn(Map.of("twTradingDate", "2026-08-13"));

        assertThat(service().getLatest().targetPriceComplete()).isFalse();
    }

    @Test
    void targetPriceCompleteIsTrueOnlyWhenAllItemsAreTargetSession() {
        when(snapshots.findLatestWithStocks()).thenReturn(Optional.of(snapshot(10L)));
        when(prices.getLiveAssets()).thenReturn(live(10L, "TARGET_SESSION_PRICE", "TARGET_SESSION_PRICE"));
        when(prices.getMarketStatus()).thenReturn(Map.of("twTradingDate", "2026-08-13"));

        assertThat(service().getLatest().targetPriceComplete()).isTrue();
    }

    /** Requirement 163／Task 452.5：owner-explicit 版本不得呼叫依賴 ownerFilter 的無參數查詢。 */
    @Test
    void ownerExplicitReadUsesOwnerQueryAndLoadedEntityOnly() {
        AssetSnapshot latest = snapshot(10L);
        AssetSnapshotDto.SnapshotDetailResponse detail = mock(AssetSnapshotDto.SnapshotDetailResponse.class);
        StockPriceService.LiveAssetsResponse live = live(10L, "TARGET_SESSION_PRICE");
        when(snapshots.findLatestWithStocksByOwnerUserId(7L)).thenReturn(Optional.of(latest));
        when(assets.getSnapshotDetail(latest)).thenReturn(detail);
        when(prices.getLiveAssets(latest)).thenReturn(live);
        when(prices.getMarketStatus()).thenReturn(Map.of("twTradingDate", "2026-08-13"));

        var response = service().getLatestForOwner(7L);

        assertThat(response.snapshot()).isSameAs(detail);
        assertThat(response.liveAssets()).isSameAs(live);
        assertThat(response.targetPriceComplete()).isTrue();
        assertThat(response.valuationPolicy()).isEqualTo("TARGET_SESSION_WITH_EXPLICIT_FALLBACK");
        verify(snapshots, never()).findLatestWithStocks();
        verify(prices, never()).getLiveAssets();
        verify(assets, never()).getSnapshotDetail(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void ownerExplicitReadFailsClosedWithoutSnapshotOrOnMismatch() {
        when(snapshots.findLatestWithStocksByOwnerUserId(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().getLatestForOwner(7L)).isInstanceOf(NoSuchElementException.class);

        AssetSnapshot latest = snapshot(10L);
        when(snapshots.findLatestWithStocksByOwnerUserId(8L)).thenReturn(Optional.of(latest));
        when(prices.getLiveAssets(latest)).thenReturn(live(11L, "TARGET_SESSION_PRICE"));
        assertThatThrownBy(() -> service().getLatestForOwner(8L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不一致");
        verify(snapshots, never()).findLatestWithStocks();
    }

    private LatestAssetsService service() {
        return new LatestAssetsService(snapshots, assets, prices);
    }

    private static AssetSnapshot snapshot(long id) {
        return AssetSnapshot.builder().id(id).snapshotDate(LocalDate.of(2026, 8, 13)).build();
    }

    private static StockPriceService.LiveAssetsResponse live(long id, String... sources) {
        List<StockPriceService.LiveStockItem> stocks = java.util.Arrays.stream(sources)
                .map(source -> new StockPriceService.LiveStockItem("2330", "台積電", "台股",
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, "2026-08-13",
                        null, null, null, null, 1L, "TEST", "2026-08-13", source))
                .toList();
        return new StockPriceService.LiveAssetsResponse(id, "2026-08-13", BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, stocks,
                false, false, false, null);
    }
}
