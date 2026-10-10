package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Requirements 111: no refresh/snapshot path; every valid public projection performs exactly one current read. */
class PublicTradingRadarProjectionServiceTest {

    @Test
    void listProjectsACompactCurrentResultWithExactlyOneRead() {
        TradingRadarService currentReader = mock(TradingRadarService.class);
        TradingRadarDto.Response current = emptyCurrent();
        when(currentReader.getCurrent()).thenReturn(current);

        var result = new PublicTradingRadarProjectionService(currentReader).list();

        assertThat(result.ruleVersion()).isEqualTo("RULES");
        assertThat(result.actionPolicyVersion()).isEqualTo("GATE");
        assertThat(result.generatedAt()).isEqualTo("2026-08-26T00:00:00Z");
        assertThat(result.stocks()).isEmpty();
        assertThat(result.publicInformation()).isEmpty();
        verify(currentReader).getCurrent();
    }

    @Test
    void malformedSelectorDoesNotReadCurrentAndMissingExactPairIsA404AfterOneRead() {
        TradingRadarService invalidReader = mock(TradingRadarService.class);
        PublicTradingRadarProjectionService invalidService = new PublicTradingRadarProjectionService(invalidReader);

        assertThatThrownBy(() -> invalidService.stock("bad!", "台股"))
                .isInstanceOf(PublicTradingRadarProjectionService.PublicTradingRadarProjectionException.class)
                .satisfies(error -> assertThat(
                        ((PublicTradingRadarProjectionService.PublicTradingRadarProjectionException) error).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(invalidReader);

        TradingRadarService missingReader = mock(TradingRadarService.class);
        when(missingReader.getCurrent()).thenReturn(emptyCurrent());
        PublicTradingRadarProjectionService missingService = new PublicTradingRadarProjectionService(missingReader);

        assertThatThrownBy(() -> missingService.stock("2330", "台股"))
                .isInstanceOf(PublicTradingRadarProjectionService.PublicTradingRadarProjectionException.class)
                .satisfies(error -> assertThat(
                        ((PublicTradingRadarProjectionService.PublicTradingRadarProjectionException) error).status())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(missingReader).getCurrent();
    }

    @Test void listAndDetailProjectTheSameImmutableIntradayReceiptWithoutRecalculation() throws Exception {
        var stock = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                {"stockCode":"2330","market":"台股","intradayCandleConfirmation":{
                 "status":"WAIT","reason":"ONE_MINUTE_WEAKENING","sourceDate":"2026-10-08",
                 "observedAt":"2026-10-08T01:13:59Z","lastCompletedAt":"2026-10-08T01:12:00Z",
                 "fiveMinuteAt":"2026-10-08T01:10:00Z","oneMinuteAt":"2026-10-08T01:12:00Z",
                 "aggregationSource":"LOCAL_AGGREGATED_FUBON_1M"}}
                """, TradingRadarDto.StockDecision.class);
        TradingRadarService reader = mock(TradingRadarService.class);
        when(reader.getCurrent()).thenReturn(new TradingRadarDto.Response("RULES", "GATE",
                "2026-10-08T01:14:00Z", null, null, List.of(stock), 0, List.of()));
        var projection = new PublicTradingRadarProjectionService(reader);
        assertThat(projection.list().stocks().getFirst().intradayCandleConfirmation())
                .isSameAs(stock.intradayCandleConfirmation());
        assertThat(projection.stock("2330", "台股").stock().intradayCandleConfirmation())
                .isSameAs(stock.intradayCandleConfirmation());
        org.mockito.Mockito.verify(reader, org.mockito.Mockito.times(2)).getCurrent();
        org.mockito.Mockito.verifyNoMoreInteractions(reader);
    }

    private static TradingRadarDto.Response emptyCurrent() {
        return new TradingRadarDto.Response("RULES", "GATE", "2026-08-26T00:00:00Z",
                null, null, List.of(), 0, List.of());
    }
}
