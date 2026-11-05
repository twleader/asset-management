package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividend.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class CanonicalDividendProjectionPollerTest {
    @Test void disabledNeverTouchesRepositoryOrReconciliation(){
        var repo=mock(CanonicalDividendRepository.class);var service=mock(CanonicalDividendReconciliationService.class);
        new CanonicalDividendProjectionPoller(repo,service,false).poll();verifyNoInteractions(repo,service);
    }
    @Test void successfulRoundAdvancesToSafeBoundaryAndRestartConservativelyReplays(){
        var repo=mock(CanonicalDividendRepository.class);var service=mock(CanonicalDividendReconciliationService.class);
        when(repo.observationsAfter(0,100)).thenReturn(new ObservationBatch(10000,List.of(new Key("2330","台股"))));
        when(repo.observationsAfter(10000,100)).thenReturn(new ObservationBatch(10000,List.of()));
        when(service.preview("2330","台股")).thenReturn(new CanonicalDividendReconciliationService.Preview("2330","台股","token",false,List.of(),1,1,"fixture"));
        var poller=new CanonicalDividendProjectionPoller(repo,service,true);poller.poll();poller.poll();
        verify(repo).observationsAfter(10000,100);verify(service).apply("2330","台股","token");
        new CanonicalDividendProjectionPoller(repo,service,true).poll();verify(repo,times(2)).observationsAfter(0,100);
    }
    @Test void failedRoundKeepsWatermarkAndBackoffSuppressesImmediateRetry(){
        var repo=mock(CanonicalDividendRepository.class);var service=mock(CanonicalDividendReconciliationService.class);
        when(repo.observationsAfter(0,100)).thenReturn(new ObservationBatch(10000,List.of(new Key("2330","台股"))));
        when(service.preview("2330","台股")).thenThrow(new IllegalStateException("temporary"));
        var poller=new CanonicalDividendProjectionPoller(repo,service,true);poller.poll();poller.poll();
        verify(repo,times(1)).observationsAfter(0,100);
        org.springframework.test.util.ReflectionTestUtils.setField(poller,"retryAfter",0L);poller.poll();verify(repo,times(2)).observationsAfter(0,100);
    }
}
