package com.steven.assets.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoryApplicationServiceTest {
    private final HistoricalDataService history = mock(HistoricalDataService.class);
    private final ExcelExportService excel = mock(ExcelExportService.class);
    private final MarketHistoryApplicationService service = new MarketHistoryApplicationService(history,mock(MarketDataService.class),excel);
    @Test
    void refreshRetainsBackfillThenBotThenPurgeAndStopsOnFailure() {
        when(history.backfillExchangeRate(eq("USD"),any())).thenReturn(4);
        assertThat(service.refreshExchangeRate("USD")).isEqualTo(Map.of("backfilled",4,"currency","USD","botFetched",true));
        var order = inOrder(history);
        order.verify(history).backfillExchangeRate("USD",LocalDate.now().minusDays(10));
        order.verify(history).fetchBotExchangeRate("USD");
        order.verify(history).purgeOldExchangeRates("USD",10);
        reset(history);
        doThrow(new IllegalStateException("upstream unavailable")).when(history).fetchBotExchangeRate("USD");
        assertThatThrownBy(() -> service.refreshExchangeRate("USD")).hasMessage("upstream unavailable");
        verify(history,never()).purgeOldExchangeRates(anyString(),anyInt());
    }
    @Test
    void exchangeListWithoutDatesKeepsAllRowsAndOnlyMissingDatesReceiveDefaults() {
        service.exchangeRates("USD",null,null);
        verify(history).getAllExchangeRates("USD");
        LocalDate end = LocalDate.of(2020,1,1);
        service.exchangeRates("USD",null,end);
        // Existing list behavior does not reject an inverted interval.
        verify(history).getExchangeRateHistory("USD",LocalDate.now().minusYears(10),end);
        service.backfillExchangeRate("USD",null);
        verify(history).backfillExchangeRateFrom("USD",LocalDate.now().minusYears(10));
    }
    @Test
    void exportsAndCommodityQueriesRejectInvertedRangesBeforeDataAccess() throws Exception {
        LocalDate start = LocalDate.of(2026,10,21), end = start.minusDays(1);
        assertThatThrownBy(() -> service.commodities(start,end)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.exportExchangeRates("USD",start,end)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.exportCommodities(start,end)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(history,excel);
        when(excel.exportCommodityPrices(any(),any())).thenReturn(new byte[]{1});
        var export = service.exportCommodities(null,null);
        assertThat(export.start()).isEqualTo(LocalDate.now().minusYears(10));
        assertThat(export.end()).isEqualTo(LocalDate.now());
        assertThat(export.content()).containsExactly(1);
    }
    @Test
    void indexRefreshIntradayAndExportKeepTheirDistinctAllowlists() throws Exception {
        var macro = mock(MacroHistoryService.class);
        var application = new MacroHistoryApplicationService(macro,excel);
        when(macro.refreshUsIndexDaily("SP500TR")).thenReturn(Map.of("upserted",1));
        assertThat(application.refreshUsDaily("SP500TR")).containsEntry("upserted",1);
        assertThat(application.refreshUsDaily("bad")).containsEntry("upserted",0).containsKey("error");
        verify(macro,never()).refreshUsIndexDaily("bad");
        assertThatThrownBy(() -> application.intraday("SP500TR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> application.exportIndex("SP500TR",null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> application.exportIndex("TPEX",LocalDate.now(),LocalDate.now().minusDays(1))).hasMessage("起始日不可晚於結束日");
        when(excel.exportIndexDaily(eq("TPEX"),any(),any())).thenReturn(new byte[]{2});
        var export = application.exportIndex(" tpex ",null,null);
        assertThat(export.code()).isEqualTo("TPEX");
        assertThat(export.content()).containsExactly(2);
        application.intraday("TPEX");
        application.intraday("TWSE");
        verify(macro).fetchIndexIntraday("TPEX");
        verify(macro).fetchIndexIntraday("TWSE");
    }
}
