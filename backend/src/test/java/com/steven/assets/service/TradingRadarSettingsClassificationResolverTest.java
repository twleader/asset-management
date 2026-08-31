package com.steven.assets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.steven.assets.model.Stock;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Radar projection delegates to the same authoritative Settings classifier as the settings page. */
class TradingRadarSettingsClassificationResolverTest {

    @Test
    void delegatesNormalRowToSharedSettingsResolverAndPreservesAllSourcesAndOverrides() {
        SettingsClassificationResolver shared = mock(SettingsClassificationResolver.class);
        TradingRadarSettingsClassificationResolver resolver = new TradingRadarSettingsClassificationResolver(shared);
        Stock stock = Stock.builder().code("2330").market("台股").name("台積電").assetClass("STOCK").build();
        SettingsClassificationResolver.Context context = new SettingsClassificationResolver.Context(
                Map.of("台股|2330", new BigDecimal("0.055")), new BigDecimal("0.05"));
        SettingsClassificationResolver.Resolution expected = new SettingsClassificationResolver.Resolution(
                AssetClassifier.STOCK, "OVERRIDE", AssetClassifier.INCOME, "RULE", null, null,
                "STOCK", null, null);
        when(shared.safeContext()).thenReturn(context);
        when(shared.resolve(eq(stock), eq("2330"), eq("台股"), eq("台積電"), eq(context))).thenReturn(expected);

        var resolved = resolver.resolve(stock, "2330", "台股", "台積電");

        assertThat(resolved.effectiveAssetClass()).isEqualTo(AssetClassifier.STOCK);
        assertThat(resolved.assetClassSource()).isEqualTo("OVERRIDE");
        assertThat(resolved.effectiveStockStyle()).isEqualTo(AssetClassifier.INCOME);
        assertThat(resolved.stockStyleSource()).isEqualTo("RULE");
        assertThat(resolved.assetClassOverride()).isEqualTo("STOCK");
        verify(shared).resolve(stock, "2330", "台股", "台積電", context);
    }

    @Test
    void excludesOnlyTaiwanMarketIndexFromSettingsProjection() {
        SettingsClassificationResolver shared = mock(SettingsClassificationResolver.class);
        TradingRadarSettingsClassificationResolver resolver = new TradingRadarSettingsClassificationResolver(shared);

        assertThat(resolver.resolve(null, "0000", "台股", "台股大盤")).isNull();
        verifyNoInteractions(shared);
    }

    @Test
    void supplementalClassificationFailsSoftButRemainsPresentForNonIndexRow() {
        SettingsClassificationResolver shared = mock(SettingsClassificationResolver.class);
        TradingRadarSettingsClassificationResolver resolver = new TradingRadarSettingsClassificationResolver(shared);
        when(shared.safeContext()).thenThrow(new IllegalStateException("temporary settings read failure"));

        var resolved = resolver.resolve(null, "AAPL", "美股", "Apple");

        assertThat(resolved).isNotNull();
        assertThat(resolved.effectiveAssetClass()).isNull();
        assertThat(resolved.assetClassSource()).isNull();
    }
}
