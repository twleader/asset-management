package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.model.marketdata.DividendData.*;
import com.steven.assets.externalmaterials.model.marketdata.DividendEvidenceData.PersistResult;
import com.steven.assets.externalmaterials.service.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DividendFinMindTransportTest {
    static final Instant T0=Instant.parse("2026-11-05T09:00:00Z");
    static final LocalDate START=LocalDate.of(2015,11,5);
    static final String EMPTY="{\"status\":200,\"data\":[]}";
    static final String QUOTA="{\"status\":402,\"msg\":\"Requests reach the upper limit. https://finmindtrade.com/\",\"token_tail\":\"\"}";
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(T0);
        void advance(Duration d) { time.updateAndGet(t->t.plus(d)); }
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
        public Instant instant(){return time.get();}
    }
    static HttpResponse<String> response(int status,String body,String retry) {
        return new HttpResponse<>() {
            public int statusCode(){return status;}
            public String body(){return body;}
            public HttpHeaders headers(){return HttpHeaders.of(retry==null?Map.of():Map.of("Retry-After",List.of(retry)),(a,b)->true);}
            public HttpRequest request(){return null;}
            public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}
            public Optional<javax.net.ssl.SSLSession> sslSession(){return Optional.empty();}
            public java.net.URI uri(){return java.net.URI.create("https://api.finmindtrade.com");}
            public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
        };
    }
    @Test void cacheRequiresCoveringStartKeepsOriginalUrlTimeAndDoesNotSlideItsFiveMinuteTtl() throws Exception {
        var clock=new MutableClock();var http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,EMPTY,null));
        var transport=new DividendFinMindTransport("same-identity",clock,http);
        var original=transport.fetch("TaiwanStockDividend","2330",START);
        clock.advance(Duration.ofMinutes(4));
        var narrow=transport.fetch("TaiwanStockDividend","2330",START.plusYears(1));
        assertThat(narrow.cacheHit()).isTrue();assertThat(narrow.capturedAt()).isEqualTo(T0);
        assertThat(narrow.actualUrl()).isEqualTo(original.actualUrl()).contains("2015-11-05");
        verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        clock.advance(Duration.ofMinutes(1));
        assertThat(transport.fetch("TaiwanStockDividend","2330",START).cacheHit()).isFalse();
        assertThat(transport.fetch("TaiwanStockDividend","2330",START.minusYears(1)).cacheHit()).isFalse();
        verify(http,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        ArgumentCaptor<HttpRequest> requests=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http,times(3)).send(requests.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues()).allMatch(r->r.headers().firstValue("Authorization").orElse("").equals("Bearer same-identity"));
    }
    @Test void cacheIsBoundedAndFailedOrMalformedDocumentsAreNeverSuccessfulCacheEntries() throws Exception {
        var clock=new MutableClock();var http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,EMPTY,null));
        var transport=new DividendFinMindTransport("",clock,http);
        for(int i=0;i<257;i++) transport.fetch("TaiwanStockDividend","S"+i,START);
        assertThat(transport.cachedDatasetCount()).isEqualTo(256);
        assertThat(transport.fetch("TaiwanStockDividend","S0",START).cacheHit()).isFalse();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(503,"unavailable",null),response(200,"{\"status\":500,\"data\":[]}",null),response(200,EMPTY,null));
        assertThat(transport.fetch("TaiwanStockDividend","ERR",START).failureKind()).isEqualTo(SourceFailureKind.HTTP_ERROR);
        assertThat(transport.fetch("TaiwanStockDividend","ERR",START).failureKind()).isEqualTo(SourceFailureKind.SCHEMA_ERROR);
        assertThat(transport.fetch("TaiwanStockDividend","ERR",START).failureKind()).isEqualTo(SourceFailureKind.NONE);
    }
    @Test void confirmedQuotaSharesCooldownButDoesNotInventHttp402ForOtherCodesAndKeepsCachedFactsReadable() throws Exception {
        var clock=new MutableClock();var http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,EMPTY,null),response(402,QUOTA,"1800"));
        var transport=new DividendFinMindTransport("",clock,http);
        var old=transport.fetch("TaiwanStockDividend","OLD",START);
        var quota=transport.fetch("TaiwanStockDividend","A",START);
        assertThat(quota.failureKind()).isEqualTo(SourceFailureKind.QUOTA_EXHAUSTED);
        assertThat(quota.httpStatus()).isEqualTo(402);assertThat(quota.capturedAt()).isEqualTo(T0);
        assertThat(transport.nextQuotaRetryAt()).isEqualTo(T0.plusSeconds(1800));
        var shortCircuited=transport.fetch("TaiwanStockDividendResult","B",START);
        assertThat(shortCircuited.failureKind()).isEqualTo(SourceFailureKind.COOLDOWN);
        assertThat(shortCircuited.httpStatus()).isNull();assertThat(shortCircuited.capturedAt()).isNull();assertThat(shortCircuited.actualUrl()).isNull();
        assertThat(shortCircuited.errorReason()).doesNotContain("402");
        assertThat(transport.fetch("TaiwanStockDividend","OLD",START).capturedAt()).isEqualTo(old.capturedAt());
        clock.advance(Duration.ofMinutes(16));assertThat(transport.quotaRetryReady()).isFalse();
        verify(http,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void bodyMustConfirmQuotaAndRetryAfterHttpDateCanExtendLocalBackoff() throws Exception {
        var clock=new MutableClock();var http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(
                response(402,"{\"status\":402,\"msg\":\"permission denied\"}",null),
                response(402,QUOTA,"Thu, 5 Nov 2026 09:45:00 GMT"));
        var transport=new DividendFinMindTransport("",clock,http);
        assertThat(transport.fetch("TaiwanStockDividend","AUTH",START).failureKind()).isEqualTo(SourceFailureKind.HTTP_ERROR);
        assertThat(transport.quotaRetryReady()).isTrue();
        transport.fetch("TaiwanStockDividend","LIMIT",START);
        assertThat(transport.nextQuotaRetryAt()).isEqualTo(T0.plus(Duration.ofMinutes(45)));
    }
    @Test void localBackoffGrowsFiveTenFifteenMinutesAndRecoveryResetsItWithoutClaimingProviderReset() throws Exception {
        var clock=new MutableClock();var http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(402,QUOTA,null),response(402,QUOTA,null),response(402,QUOTA,null),response(200,EMPTY,null));
        var transport=new DividendFinMindTransport("",clock,http);
        transport.fetch("TaiwanStockDividend","A",START);assertThat(transport.nextQuotaRetryAt()).isEqualTo(T0.plusSeconds(300));
        clock.advance(Duration.ofMinutes(5));transport.fetch("TaiwanStockDividend","A",START);assertThat(transport.nextQuotaRetryAt()).isEqualTo(clock.instant().plusSeconds(600));
        clock.advance(Duration.ofMinutes(10));transport.fetch("TaiwanStockDividend","A",START);assertThat(transport.nextQuotaRetryAt()).isEqualTo(clock.instant().plusSeconds(900));
        clock.advance(Duration.ofMinutes(15));transport.fetch("TaiwanStockDividend","A",START);assertThat(transport.nextQuotaRetryAt()).isNull();
    }
    @Test void sameDatasetInflightIsSerializedAndOnlyOneCrossCodeQuotaProbeCanRun() throws Exception {
        var clock=new MutableClock();var http=mock(HttpClient.class);var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var calls=new AtomicInteger();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{
            int n=calls.incrementAndGet();if(n==1){started.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();}
            return response(200,EMPTY,null);
        });
        var transport=new DividendFinMindTransport("",clock,http);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(()->transport.fetch("TaiwanStockDividend","A",START));assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            var two=executor.submit(()->transport.fetch("TaiwanStockDividend","A",START));release.countDown();
            one.get(3,TimeUnit.SECONDS);assertThat(two.get(3,TimeUnit.SECONDS).cacheHit()).isTrue();assertThat(calls).hasValue(1);
        }
        var probeStarted=new CountDownLatch(1);var probeRelease=new CountDownLatch(1);calls.set(0);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{
            if(calls.incrementAndGet()==1)return response(402,QUOTA,null);
            probeStarted.countDown();assertThat(probeRelease.await(3,TimeUnit.SECONDS)).isTrue();return response(200,EMPTY,null);
        });
        var quotaTransport=new DividendFinMindTransport("",clock,http);quotaTransport.fetch("TaiwanStockDividend","LIMIT",START);clock.advance(Duration.ofMinutes(5));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var probe=executor.submit(()->quotaTransport.fetch("TaiwanStockDividend","B",START));assertThat(probeStarted.await(2,TimeUnit.SECONDS)).isTrue();
            var other=executor.submit(()->quotaTransport.fetch("TaiwanStockDividend","C",START));
            assertThat(other.get(2,TimeUnit.SECONDS).failureKind()).isEqualTo(SourceFailureKind.COOLDOWN);assertThat(calls).hasValue(2);
            probeRelease.countDown();probe.get(3,TimeUnit.SECONDS);
        }
    }
    @Test void fullHistoricalRouterFutureAndPersisterChainRetainsT0CaptureAtT1AndDoesNotPromoteCompleteness() throws Exception {
        var clock=new MutableClock();var http=mock(HttpClient.class);
        String raw="""
                {"status":200,"data":[{"year":"115q1","CashEarningsDistribution":6.00003573,"CashStatutorySurplus":0,"StockEarningsDistribution":0,"StockStatutorySurplus":0,"CashExDividendTradingDate":"2026-03-17"},
                {"year":"115q2","CashEarningsDistribution":7,"CashStatutorySurplus":0,"StockEarningsDistribution":0,"StockStatutorySurplus":0,"CashExDividendTradingDate":"2026-12-10"}]}
                """;
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,raw,null));
        var transport=new DividendFinMindTransport("",clock,http);
        var tw=mock(TaiwanOfficialDividendCalendarClient.class);var us=mock(NasdaqDividendCalendarClient.class);
        ObjectProvider<DividendFetchClient> provider=mock();
        var router=new MarketDividendUpcomingScopeClient(us,tw,provider);
        var client=new DividendFetchClient("",router,clock,http,transport);when(provider.getIfAvailable()).thenReturn(client);
        when(tw.fetch(anyString(),anyString(),any(),any())).thenAnswer(inv->{clock.advance(Duration.ofSeconds(30));return DividendUpcomingScopeClient.UpcomingScope.unavailable("official unavailable");});
        var snapshots=mock(DividendSnapshotStore.class);when(snapshots.record(anyString(),anyString(),any(),any())).thenReturn(new PersistResult(1,"hash",false,"PARTIAL"));
        var marketClock=mock(MarketClock.class);when(marketClock.instant()).thenAnswer(inv->clock.instant());
        var result=new DividendPersister(client,mock(StockSourceQuery.class),snapshots,marketClock).syncOneDetailed("2330","台股",11);
        assertThat(clock.instant()).isAfter(T0);assertThat(result.sourceStatuses()).hasSize(2).allSatisfy(s->{
            assertThat(s.capturedAt()).isEqualTo(T0);assertThat(s.observedAt()).isEqualTo(T0);assertThat(s.status()).isEqualTo(FetchStatus.PARTIAL);
        });
        ArgumentCaptor<DividendFetchResult> fetched=ArgumentCaptor.forClass(DividendFetchResult.class);
        verify(snapshots,times(2)).record(eq("2330"),eq("台股"),fetched.capture(),eq(T0));
        assertThat(fetched.getAllValues()).allSatisfy(f->{assertThat(f.sourceAvailableAt()).isNull();assertThat(f.sourceUrls()).singleElement().asString().contains("start_date=2015-11-05");assertThat(f.complete()).isFalse();});
        assertThat(fetched.getAllValues().get(1).events()).singleElement().satisfies(e->assertThat(e.cashDividend()).isEqualByComparingTo("7"));
        verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void actual402ThenLocalCooldownQueuesEveryOriginalScopeAndOneProbeRecoversOnlyThoseStocks() throws Exception {
        var clock=new MutableClock();var http=mock(HttpClient.class);var limited=new AtomicBoolean(true);var calls=new AtomicInteger();
        String raw="""
                {"status":200,"data":[{"year":"115","CashEarningsDistribution":0.5,"CashStatutorySurplus":0,"StockEarningsDistribution":0,"StockStatutorySurplus":0,"CashExDividendTradingDate":"2026-03-17"}]}
                """;
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{calls.incrementAndGet();return limited.get()?response(402,QUOTA,null):response(200,raw,null);});
        var transport=new DividendFinMindTransport("",clock,http);var tw=mock(TaiwanOfficialDividendCalendarClient.class);
        when(tw.fetch(anyString(),anyString(),any(),any())).thenReturn(DividendUpcomingScopeClient.UpcomingScope.unavailable("official unavailable"));
        ObjectProvider<DividendFetchClient> provider=mock();
        var router=new MarketDividendUpcomingScopeClient(mock(NasdaqDividendCalendarClient.class),tw,provider);
        var client=new DividendFetchClient("",router,clock,http,transport);when(provider.getIfAvailable()).thenReturn(client);
        var store=mock(DividendSnapshotStore.class);when(store.record(anyString(),anyString(),any(),any())).thenReturn(new PersistResult(1,"hash",false,"PARTIAL"));
        var marketClock=mock(MarketClock.class);when(marketClock.instant()).thenAnswer(inv->clock.instant());
        var source=mock(StockSourceQuery.class);var persister=new DividendPersister(client,source,store,marketClock);
        var first=persister.syncOneDetailed("A","台股",11);
        assertThat(first.sourceStatuses().getFirst().failureKind()).isEqualTo(SourceFailureKind.QUOTA_EXHAUSTED);
        for(var request:List.of(Map.entry("B",10),Map.entry("C",12))) {
            var local=persister.syncOneDetailed(request.getKey(),"台股",request.getValue());
            assertThat(local.quotaRetryPending()).isTrue();
            assertThat(local.sourceStatuses()).allSatisfy(status->{assertThat(status.failureKind()).isEqualTo(SourceFailureKind.COOLDOWN);assertThat(status.capturedAt()).isNull();assertThat(status.reason()).doesNotContain("402");});
        }
        assertThat(calls).hasValue(1);clearInvocations(store);persister.retryPendingQuota();verifyNoInteractions(store);assertThat(calls).hasValue(1);
        clock.advance(Duration.ofMinutes(5));limited.set(false);persister.retryPendingQuota();assertThat(calls).hasValue(4);
        ArgumentCaptor<HttpRequest> requests=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http,times(4)).send(requests.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues().stream().map(r->r.uri().getQuery()).toList()).containsExactly(
                "dataset=TaiwanStockDividend&data_id=A&start_date=2015-11-05",
                "dataset=TaiwanStockDividend&data_id=A&start_date=2015-11-05",
                "dataset=TaiwanStockDividend&data_id=B&start_date=2016-11-05",
                "dataset=TaiwanStockDividend&data_id=C&start_date=2014-11-05");
        clearInvocations(store);persister.retryPendingQuota();verifyNoInteractions(store,source);assertThat(calls).hasValue(4);
    }

    @Test void bothProviderFailuresRetainResultProviderAndItsOwnHttpUrlAndCapture() throws Exception {
        var clock=new MutableClock();var http=mock(HttpClient.class);var requests=new ArrayList<HttpRequest>();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{
            HttpRequest request=inv.getArgument(0);requests.add(request);clock.advance(Duration.ofSeconds(10));
            return requests.size()==1 ? response(503,"{\"status\":503,\"msg\":\"primary unavailable\"}",null)
                    : response(500,"{\"status\":500,\"msg\":\"result unavailable\"}",null);
        });
        var historical=new DividendFetchClient("",null,clock,http).fetchObservations("2303","台股",11).getFirst();
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().uri().toString()).contains("dataset=TaiwanStockDividend&");
        assertThat(requests.getLast().uri().toString()).contains("dataset=TaiwanStockDividendResult&");
        assertThat(historical.source()).isEqualTo("FinMind[TaiwanStockDividendResult]");
        assertThat(historical.status()).isEqualTo(FetchStatus.FAILED);assertThat(historical.events()).isEmpty();
        assertThat(historical.sourceUrls()).containsExactly(requests.getLast().uri().toString());
        assertThat(historical.capturedAt()).isEqualTo(T0.plusSeconds(20)).isNotEqualTo(T0.plusSeconds(10));
        assertThat(historical.failureKind()).isEqualTo(SourceFailureKind.HTTP_ERROR);
        assertThat(historical.dateGroupsComplete()).isFalse();assertThat(historical.errorReason()).contains("HTTP 503","HTTP 500");
    }

}
