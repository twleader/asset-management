package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient;
import com.steven.assets.externalmaterials.model.marketdata.DividendData.*;
import com.steven.assets.externalmaterials.model.marketdata.DividendEvidenceData.PersistResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DividendQuotaRetryTest {
    static final Instant T0=Instant.parse("2026-11-05T09:00:00Z");
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(T0);
        void advance(Duration duration){time.updateAndGet(t->t.plus(duration));}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
        public Instant instant(){return time.get();}
    }
    static DividendFetchResult result(SourceFailureKind kind,Instant capture) {
        boolean quota=kind.quotaRelated();
        return new DividendFetchResult("FinMind[TaiwanStockDividend]",List.of(),kind==SourceFailureKind.NONE?FetchStatus.PARTIAL:FetchStatus.FAILED,
                LocalDate.of(2015,11,5),LocalDate.of(2026,11,5),null,quota?kind.name():kind==SourceFailureKind.NONE?"合法空資料不證明不配息":"schema/source unknown",List.of(),2,"NOMINAL_PER_EX_DATE",kind==SourceFailureKind.NONE,capture,kind);
    }
    static DividendSnapshotStore snapshots() {
        var store=mock(DividendSnapshotStore.class);
        when(store.record(anyString(),anyString(),any(),any())).thenReturn(new PersistResult(1,"hash",false,"PARTIAL"));return store;
    }
    @Test void emptyOrNotYetDueQueueDoesNoFetchAndNoDatabaseWork() {
        var clock=new MutableClock();var client=mock(DividendFetchClient.class);var store=snapshots();var source=mock(StockSourceQuery.class);
        var persister=new DividendPersister(client,source,store,clock);
        persister.retryPendingQuota();verifyNoInteractions(client,store,source);
        when(client.fetchObservations("A","台股",11)).thenReturn(List.of(result(SourceFailureKind.QUOTA_EXHAUSTED,T0)));
        assertThat(persister.syncOneDetailed("A","台股",11).quotaRetryPending()).isTrue();
        clearInvocations(client,store,source);when(client.quotaRetryReady()).thenReturn(false);
        persister.retryPendingQuota();verify(client).quotaRetryReady();verifyNoMoreInteractions(client);verifyNoInteractions(store,source);
    }
    @Test void firstActual402AndSubsequentCooldownAllRetainAuthorizedScopesAndRecoveryIsBoundedToTen() {
        var clock=new MutableClock();var client=mock(DividendFetchClient.class);var store=snapshots();var source=mock(StockSourceQuery.class);
        var persister=new DividendPersister(client,source,store,clock);
        when(client.fetchObservations(anyString(),eq("台股"),anyInt())).thenAnswer(inv->List.of(result(inv.getArgument(0).equals("S0")?SourceFailureKind.QUOTA_EXHAUSTED:SourceFailureKind.COOLDOWN,inv.getArgument(0).equals("S0")?T0:null)));
        for(int i=0;i<12;i++)assertThat(persister.syncOneDetailed("S"+i,"台股",i%2==0?11:10).quotaRetryPending()).isTrue();
        persister.syncOneDetailed("S0","台股",12);assertThat(persister.pendingQuotaCount()).isEqualTo(12);
        clearInvocations(client,store,source);when(client.quotaRetryReady()).thenReturn(true);
        when(client.fetchObservations(anyString(),eq("台股"),anyInt())).thenReturn(List.of(result(SourceFailureKind.NONE,T0)));
        persister.retryPendingQuota();assertThat(persister.pendingQuotaCount()).isEqualTo(2);
        verify(client,times(10)).fetchObservations(anyString(),eq("台股"),anyInt());verify(client).fetchObservations("S0","台股",12);
        persister.retryPendingQuota();assertThat(persister.pendingQuotaCount()).isZero();
        verify(client,times(12)).fetchObservations(anyString(),eq("台股"),anyInt());verifyNoInteractions(source);
        clearInvocations(client,store);persister.retryPendingQuota();verifyNoInteractions(client,store);
    }
    @Test void nonQuotaAuthSchemaAndLegalEmptyStopQuotaRetryWithoutClaimingFinancialCompleteness() {
        for(SourceFailureKind terminal:List.of(SourceFailureKind.NONE,SourceFailureKind.HTTP_ERROR,SourceFailureKind.SCHEMA_ERROR)) {
            var client=mock(DividendFetchClient.class);var store=snapshots();var clock=new MutableClock();
            when(client.fetchObservations("A","台股",11)).thenReturn(List.of(result(SourceFailureKind.COOLDOWN,null)),List.of(result(terminal,T0)));
            var persister=new DividendPersister(client,mock(StockSourceQuery.class),store,clock);
            persister.syncOneDetailed("A","台股",11);when(client.quotaRetryReady()).thenReturn(true);
            persister.retryPendingQuota();assertThat(persister.pendingQuotaCount()).isZero();
            var detailed=persister.syncOneDetailed("A","台股",11);
            assertThat(detailed.quotaRelated()).isFalse();assertThat(detailed.written()).isZero();
            assertThat(detailed.sourceStatuses()).singleElement().satisfies(s->{assertThat(s.status()).isNotEqualTo(FetchStatus.EMPTY_COMPLETE);assertThat(s.reason()).isNotBlank();});
        }
    }
    @Test void queueCapacityRejectionIsExplicitAndTwentyFourHourDeadlineDoesNotExtendOnMerge() {
        var clock=new MutableClock();var client=mock(DividendFetchClient.class);var store=snapshots();var source=mock(StockSourceQuery.class);
        when(client.fetchObservations(anyString(),anyString(),anyInt())).thenReturn(List.of(result(SourceFailureKind.COOLDOWN,null)));
        var persister=new DividendPersister(client,source,store,clock);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(DividendPersister.class);
        var oldLevel=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.ERROR);
        try {
            for(int i=0;i<2000;i++)persister.syncOneDetailed("S"+i,"台股",10);
            var rejected=persister.syncOneDetailed("OVER","台股",10);
            assertThat(rejected.queueStatus()).isEqualTo("CAPACITY_REJECTED");assertThat(rejected.quotaRetryPending()).isFalse();assertThat(rejected.quotaRelated()).isTrue();
            assertThat(persister.pendingQuotaCount()).isEqualTo(2000);
            clock.advance(Duration.ofHours(23));assertThat(persister.syncOneDetailed("S0","台股",11).pendingYears()).isEqualTo(11);
            clock.advance(Duration.ofHours(1));clearInvocations(client,store,source);
            persister.retryPendingQuota();assertThat(persister.pendingQuotaCount()).isZero();verifyNoInteractions(client,store,source);
        } finally {logger.setLevel(oldLevel);}
    }
    @Test void olderTenYearRetryCannotRemoveNewElevenYearAuthorization() throws Exception {
        var clock=new MutableClock();var client=mock(DividendFetchClient.class);var store=snapshots();
        var persister=new DividendPersister(client,mock(StockSourceQuery.class),store,clock);
        when(client.fetchObservations("A","台股",10)).thenReturn(List.of(result(SourceFailureKind.COOLDOWN,null)));
        persister.syncOneDetailed("A","台股",10);when(client.quotaRetryReady()).thenReturn(true);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(client.fetchObservations("A","台股",10)).thenAnswer(inv->{started.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return List.of(result(SourceFailureKind.NONE,T0));});
        when(client.fetchObservations("A","台股",11)).thenReturn(List.of(result(SourceFailureKind.COOLDOWN,null)));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var running=executor.submit(persister::retryPendingQuota);assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(persister.syncOneDetailed("A","台股",11).pendingYears()).isEqualTo(11);
            release.countDown();running.get(3,TimeUnit.SECONDS);
        }
        assertThat(persister.pendingQuotaCount()).isEqualTo(1);
        when(client.fetchObservations("A","台股",11)).thenReturn(List.of(result(SourceFailureKind.NONE,T0)));
        persister.retryPendingQuota();assertThat(persister.pendingQuotaCount()).isZero();verify(client,times(2)).fetchObservations("A","台股",11);
    }
    @Test void futureCaptureIsRejectedBeforeAnyDatabaseMutationAndNonQuotaFailureNeverEnqueues() {
        var clock=new MutableClock();var client=mock(DividendFetchClient.class);var store=snapshots();
        var persister=new DividendPersister(client,mock(StockSourceQuery.class),store,clock);
        when(client.fetchObservations("A","台股",10)).thenReturn(List.of(result(SourceFailureKind.NONE,T0.plusSeconds(1))));
        assertThatThrownBy(()->persister.syncOneDetailed("A","台股",10)).hasMessage("DIVIDEND_CAPTURE_IN_FUTURE");verifyNoInteractions(store);
        when(client.fetchObservations("A","台股",10)).thenReturn(List.of(result(SourceFailureKind.SCHEMA_ERROR,T0)));
        assertThat(persister.syncOneDetailed("A","台股",10).quotaRetryPending()).isFalse();
    }
}
