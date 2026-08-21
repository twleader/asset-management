package com.steven.assets.service;

import com.steven.assets.repository.StockRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockMasterAfterCommitTest {
    @Mock StockRepository repository;
    @Mock HistoricalDataService historicalDataService;

    private RecordingExecutor executor;
    private StockMasterService service;

    @BeforeEach
    void setUp() {
        executor = new RecordingExecutor();
        service = new StockMasterService(repository, historicalDataService, executor);
        when(repository.existsByCodeAndMarket("2330", "台股")).thenReturn(false);
        lenient().when(historicalDataService.backfillSingleStock(any(), any(), any()))
                .thenReturn(Map.of("records", 1));
    }

    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rollbackSubmitsNoBackfill() {
        beginTransaction();
        service.upsert("2330", "台股", "台積電");
        assertThat(executor.executions).isZero();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(executor.executions).isZero();
        verify(historicalDataService, never()).backfillSingleStock(any(), any(), any());
    }

    @Test
    void commitSubmitsExactlyOnceAndOnlyAfterCommitCallback() {
        beginTransaction();
        service.upsert("2330", "台股", "台積電");
        assertThat(executor.executions).isZero();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(executor.executions).isEqualTo(1);
        verify(historicalDataService).backfillSingleStock(any(), any(), any());
    }

    @Test
    void noTransactionSubmitsImmediately() {
        service.upsert("2330", "台股", "台積電");

        assertThat(executor.executions).isEqualTo(1);
        verify(historicalDataService).backfillSingleStock(any(), any(), any());
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static final class RecordingExecutor extends AbstractExecutorService {
        private boolean shutdown;
        private int executions;

        @Override
        public void execute(Runnable command) {
            executions++;
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }
}
