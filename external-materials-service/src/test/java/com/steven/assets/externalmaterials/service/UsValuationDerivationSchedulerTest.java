package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.StockFundamentalFetchClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 334.4 排程本體守門：兩個步驟的順序、繞過 coverage 短路、以及逐檔 fail-soft。
 *
 * <p>這三件事做錯都不會有錯誤訊息：順序顛倒會用舊季別推導、查 coverage 會讓已覆蓋標的永遠補不到
 * Task 334.2 放寬後的舊季別、單檔失敗中止整輪則會讓後面的標的整批沒有序列。</p>
 */
class UsValuationDerivationSchedulerTest {

    @Test
    void edgarRefetchCoversEveryCodeBeforeDerivationAndNeverConsultsCoverage() {
        StockFundamentalFetchClient client = mock(StockFundamentalFetchClient.class);
        FundamentalObservationStore store = mock(FundamentalObservationStore.class);
        UsValuationDerivationService derivation = mock(UsValuationDerivationService.class);
        when(store.secEdgarUsStockCodes()).thenReturn(List.of("AMZN", "MSFT"));
        when(client.fetchSecEdgarFacts(anyString())).thenReturn(StockFundamentalFetchClient.Bundle.EMPTY);
        when(store.append(any())).thenReturn(new FundamentalObservationStore.WriteCount(0, 3, 0, 0));
        when(derivation.deriveAll()).thenReturn(
                new UsValuationDerivationService.Summary(2, 10, 0, 0, List.of()));

        var result = new UsValuationDerivationScheduler(client, store, derivation).runGuarded("test");

        InOrder order = inOrder(client, derivation);
        order.verify(client).fetchSecEdgarFacts("AMZN");
        order.verify(client).fetchSecEdgarFacts("MSFT");
        order.verify(derivation).deriveAll();
        // StockFundamentalPoller 的 coverage 短路必須被繞過：已覆蓋的標的不會自己去補更早的歷史。
        verify(store, never()).fallbackNeed(anyString(), anyString(), any());
        assertThat(result.edgarTargets()).isEqualTo(2);
        assertThat(result.edgarWritten()).isEqualTo(6);
        assertThat(result.edgarFailures()).isZero();
        assertThat(result.derivation().written()).isEqualTo(10);
    }

    @Test
    void singleCodeFailureMustNotAbortTheRoundOrSkipDerivation() {
        StockFundamentalFetchClient client = mock(StockFundamentalFetchClient.class);
        FundamentalObservationStore store = mock(FundamentalObservationStore.class);
        UsValuationDerivationService derivation = mock(UsValuationDerivationService.class);
        when(store.secEdgarUsStockCodes()).thenReturn(List.of("AMZN", "MSFT"));
        when(client.fetchSecEdgarFacts("AMZN")).thenThrow(new IllegalStateException("SEC 429"));
        when(client.fetchSecEdgarFacts("MSFT")).thenReturn(StockFundamentalFetchClient.Bundle.EMPTY);
        when(store.append(any())).thenReturn(new FundamentalObservationStore.WriteCount(0, 1, 0, 0));
        when(derivation.deriveAll()).thenReturn(
                new UsValuationDerivationService.Summary(2, 4, 0, 0, List.of()));

        var result = new UsValuationDerivationScheduler(client, store, derivation).runGuarded("test");

        verify(client).fetchSecEdgarFacts("MSFT");
        verify(derivation).deriveAll();
        assertThat(result.edgarFailures()).isEqualTo(1);
        assertThat(result.edgarWritten()).isEqualTo(1);
        assertThat(result.derivation().written()).isEqualTo(4);
    }
}
