package com.steven.assets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockStyle;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.StockStyleRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Normal stock input has one settings-equivalent result for both callers. */
@ExtendWith(MockitoExtension.class)
class SettingsClassificationResolverParityTest {
    @Mock private AssetClassifier assetClassifier;
    @Mock private AssetSnapshotRepository snapshotRepo;
    @Mock private StockStyleRepository stockStyleRepo;

    @Test
    void settingsAndRadarProjectionShareDividendThresholdRuleAndProvenance() {
        Stock stock = Stock.builder().code("2330").market("台股").name("台積電").build();
        StockHolding holding = StockHolding.builder()
                .stockCode("2330").market("台股").dividendRate(new BigDecimal("0.055")).build();
        AssetSnapshot snapshot = AssetSnapshot.builder().stocks(List.of(holding)).build();
        StockStyle income = StockStyle.builder().code(AssetClassifier.INCOME)
                .dividendThreshold(new BigDecimal("0.05")).build();
        when(snapshotRepo.findAllOrderByDateDesc()).thenReturn(List.of(snapshot));
        when(stockStyleRepo.findByCode(AssetClassifier.INCOME)).thenReturn(Optional.of(income));
        when(assetClassifier.classifyStock("2330", "台股", null)).thenReturn(AssetClassifier.STOCK);
        when(assetClassifier.classifyStockStyle("2330", "台股", null,
                new BigDecimal("0.055"), new BigDecimal("0.05"))).thenReturn(AssetClassifier.INCOME);

        SettingsClassificationResolver shared = new SettingsClassificationResolver(
                assetClassifier, snapshotRepo, stockStyleRepo);
        SettingsClassificationResolver.Context context = shared.context();
        SettingsClassificationResolver.Resolution settings = shared.resolve(stock, context);
        TradingRadarSettingsClassificationResolver.Resolution radar =
                new TradingRadarSettingsClassificationResolver(shared)
                        .resolve(stock, "2330", "台股", "台積電", context);

        assertThat(radar.effectiveAssetClass()).isEqualTo(settings.effectiveAssetClass());
        assertThat(radar.assetClassSource()).isEqualTo(settings.assetClassSource());
        assertThat(radar.effectiveStockStyle()).isEqualTo(settings.effectiveStockStyle());
        assertThat(radar.stockStyleSource()).isEqualTo(settings.stockStyleSource());
        assertThat(radar.effectiveBondTerm()).isEqualTo(settings.effectiveBondTerm());
        assertThat(radar.bondTermSource()).isEqualTo(settings.bondTermSource());
        assertThat(radar.assetClassOverride()).isEqualTo(settings.assetClassOverride());
    }
}
