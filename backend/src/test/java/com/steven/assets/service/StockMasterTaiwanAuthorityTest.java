package com.steven.assets.service;

import com.steven.assets.repository.StockRepository;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Taiwan resolution always refreshes through the trusted authority chain before master persistence. */
class StockMasterTaiwanAuthorityTest {
    @Test
    void taiwanResolverDoesNotShortCircuitAStaleLocalNameAndTrustedResultRenames() {
        StockRepository repository = mock(StockRepository.class);
        StockMasterPersistence persistence = mock(StockMasterPersistence.class);
        HistoricalDataService historical = mock(HistoricalDataService.class);
        ExecutorService executor = mock(ExecutorService.class);
        StockMasterService service = new StockMasterService(repository, persistence, historical, executor);
        when(repository.existsByCodeAndMarket("00850", "台股")).thenReturn(true);
        when(historical.fetchTwStockName("00850")).thenReturn("元大臺灣ESG永續");

        assertThat(service.resolveName(" 00850 ", "台股")).isEqualTo("元大臺灣ESG永續");

        verify(historical).fetchTwStockName("00850");
        verify(persistence).upsertTrusted("00850", "台股", "元大臺灣ESG永續");
        verify(repository, never()).findByCodeAndMarket(any(), any());
    }

    @Test
    void validTaiwanResolverResultCreatesThroughTrustedPersistence() {
        StockRepository repository = mock(StockRepository.class);
        StockMasterPersistence persistence = mock(StockMasterPersistence.class);
        HistoricalDataService historical = mock(HistoricalDataService.class);
        ExecutorService executor = mock(ExecutorService.class);
        StockMasterService service = new StockMasterService(repository, persistence, historical, executor);
        when(repository.existsByCodeAndMarket("006208", "台股")).thenReturn(false);
        when(historical.fetchTwStockName("006208")).thenReturn("富邦台50");

        assertThat(service.resolveName("006208", "台股")).isEqualTo("富邦台50");

        verify(persistence).upsertTrusted("006208", "台股", "富邦台50");
        verify(executor).submit(any(Runnable.class));
    }

    @Test
    void emptyTaiwanAuthorityResultDoesNotWriteTheMaster() {
        StockRepository repository = mock(StockRepository.class);
        StockMasterPersistence persistence = mock(StockMasterPersistence.class);
        HistoricalDataService historical = mock(HistoricalDataService.class);
        StockMasterService service = new StockMasterService(repository, persistence, historical, mock(ExecutorService.class));
        when(historical.fetchTwStockName("00850")).thenReturn(" ");

        assertThat(service.resolveName("00850", "台股")).isEmpty();

        verifyNoInteractions(persistence);
        verify(repository, never()).existsByCodeAndMarket(any(), any());
    }
}
