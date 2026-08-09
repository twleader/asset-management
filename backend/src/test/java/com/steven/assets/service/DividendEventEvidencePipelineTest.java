package com.steven.assets.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DividendEventEvidencePipelineTest {

    @Test
    void projectsCurrentStateBeforeReadingAppendOnlyEvidence() {
        DividendCurrentStateProjectionService projection =
                mock(DividendCurrentStateProjectionService.class);
        DividendEventEvidenceStore store = mock(DividendEventEvidenceStore.class);
        Instant decision = Instant.parse("2026-08-09T12:00:00Z");
        List<LocalDate> sessions = List.of(LocalDate.of(2026, 8, 10));
        when(store.resolve("AAPL", "美股", decision, sessions))
                .thenReturn(DividendEventEvidenceResolver.Resolution.MISSING);

        var result = new DividendEventEvidencePipeline(projection, store)
                .resolve("AAPL", "美股", decision, sessions);

        InOrder order = inOrder(projection, store);
        order.verify(projection).projectOne("AAPL", "美股", decision);
        order.verify(store).resolve("AAPL", "美股", decision, sessions);
        assertThat(result).isSameAs(DividendEventEvidenceResolver.Resolution.MISSING);
    }

    @Test
    void batchAsOfResolutionNeverProjectsHistoricalSignalsIntoCurrentState() {
        DividendCurrentStateProjectionService projection =
                mock(DividendCurrentStateProjectionService.class);
        DividendEventEvidenceStore store = mock(DividendEventEvidenceStore.class);
        var query = new DividendEventEvidenceBatch.Query(
                "AAPL", "美股", Instant.parse("2025-08-09T12:00:00Z"), List.of());
        var expected = List.of(new DividendEventEvidenceBatch.Result(
                query, DividendEventEvidenceResolver.Resolution.MISSING));
        when(store.resolveBatch(List.of(query))).thenReturn(expected);

        var result = new DividendEventEvidencePipeline(projection, store)
                .resolveBatch(List.of(query));

        assertThat(result).isSameAs(expected);
        verifyNoInteractions(projection);
    }
}
