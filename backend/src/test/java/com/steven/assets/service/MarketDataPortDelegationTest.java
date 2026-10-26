package com.steven.assets.service;

import com.steven.assets.dto.CrawlerExportPathDto;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.marketdata.MarketDataResults.DividendRateResult;
import com.steven.assets.model.marketdata.ReadOnlyIntradayData.ReadOnlyIntradayTicks;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.service.port.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Business rules remain testable with inward port fakes and no Spring, HTTP or Redis runtime. */
class MarketDataPortDelegationTest {
    @Test void dividendRateRetainsCacheAndMasterNameFallbackAcrossPortBoundary() {
        var remote = mock(MarketDataReadPort.class);
        var stocks = mock(StockRepository.class);
        var stock = new com.steven.assets.model.Stock();
        stock.setName("主檔名稱");
        when(stocks.findByCodeAndMarket("0050", "台股")).thenReturn(java.util.Optional.of(stock));
        when(remote.dividendRate("0050", "台股")).thenReturn(
                new DividendRateResult("0050", "台股", new BigDecimal("3.25"), "fixture", null));
        var service = new MarketDataService(remote, stocks, null);

        assertThat(service.getDividendRate("0050", "台股").stockName()).isEqualTo("主檔名稱");
        assertThat(service.getDividendRate("0050", "台股").dividendRate()).isEqualByComparingTo("3.25");
        verify(remote, times(1)).dividendRate("0050", "台股");
        verify(stocks, times(1)).findByCodeAndMarket("0050", "台股");
    }

    @Test void historicalBackfillKeepsItsFailureEnvelopeAndOptionalUntil() {
        var remote = mock(HistoricalDataPort.class);
        var service = new HistoricalDataService(null, null, null, null, null, null, remote);
        var since = LocalDate.of(2026, 10, 1);
        when(remote.backfillStock("0050", "台股", since, null))
                .thenThrow(new IllegalStateException("fixture failure"));
        assertThat(service.backfillSingleStock("0050", "台股", since)).containsAllEntriesOf(
                Map.of("stockCode", "0050", "market", "台股", "records", 0,
                        "since", since.toString(), "error", "fixture failure"));
        verify(remote).backfillStock("0050", "台股", since, null);
    }

    @Test void priceRedisMissAndMalformedValueKeepTheirExistingFallback() {
        var cache = mock(PriceCacheReadPort.class);
        var history = mock(com.steven.assets.repository.StockPriceHistoryRepository.class);
        var remote = mock(PriceRefreshPort.class);
        var service = new PriceQueryService(cache, history, remote, null);
        when(cache.get("price:美股:AAPL")).thenReturn("malformed");
        when(history.findRecentN("AAPL", "美股", 1)).thenReturn(List.of());
        assertThat(service.getLive("AAPL", "美股")).isEmpty();
        verify(history).findRecentN("AAPL", "美股", 1);
        verifyNoInteractions(remote);
    }

    @Test void readonlyProxyPreservesTheSameImmutableOutcomeWithoutOtherIo() {
        var remote = mock(ReadOnlyIntradayPort.class);
        var date = LocalDate.of(2026, 10, 26);
        var outcome = new ReadOnlyIntradayTicks(date, "UNAVAILABLE", List.of());
        when(remote.readIntradayTicks("0050", "台股", date)).thenReturn(outcome);
        assertThat(new PublicMarketDataReadOnlyService(remote).readIntradayTicks("0050", "台股", date))
                .isSameAs(outcome);
    }

    @Test void coldDividendSyncOccursBetweenTheTwoProjectionsAndRepositoryReads() {
        var repo = mock(StockDividendHistoryRepository.class);
        var projection = mock(DividendCurrentStateProjectionService.class);
        var remote = mock(DividendSyncPort.class);
        var row = new StockDividendHistory();
        row.setYear(Year.now().getValue());
        row.setSource("fixture");
        when(repo.findByStockSinceYear(eq("0050"), eq("台股"), anyInt()))
                .thenReturn(List.of(), List.of(row));
        var service = new DividendHistoryService(repo, projection, remote);
        assertThat(service.findFromDb("0050", "台股", 10).rows()).hasSize(1);
        var ordered = inOrder(projection, repo, remote);
        ordered.verify(projection).projectOne(eq("0050"), eq("台股"), any());
        ordered.verify(repo).findByStockSinceYear(eq("0050"), eq("台股"), anyInt());
        ordered.verify(remote).sync("0050", "台股");
        ordered.verify(projection).projectOne(eq("0050"), eq("台股"), any());
        ordered.verify(repo).findByStockSinceYear(eq("0050"), eq("台股"), anyInt());
    }

    @Test void crawlerCooldownRestartsOnlyAfterTheOperationReturns() {
        var remote = mock(CrawlerRunPort.class);
        var cooldown = mock(CooldownStorePort.class);
        when(cooldown.setIfAbsent(anyString(), eq("1"), any())).thenReturn(true);
        var outcome = new CrawlerExportPathDto.RunNowResponse(
                "OK", null, null, null, null, null, null, null, null, null, null, null, null);
        when(remote.run(eq(CrawlerRunPort.Operation.PUBLIC_RESCAN), eq(50L), any())).thenReturn(outcome);
        var service = new CrawlerExportPathService(null, "/home/steven", null, remote, 50, cooldown);
        assertThat(service.publicRescan()).isSameAs(outcome);
        var ordered = inOrder(cooldown, remote);
        String key = "crawler:news-poller:public-rescan:cooldown";
        var ttl = java.time.Duration.ofSeconds(30);
        ordered.verify(cooldown).setIfAbsent(key, "1", ttl);
        ordered.verify(remote).run(eq(CrawlerRunPort.Operation.PUBLIC_RESCAN), eq(50L), any());
        ordered.verify(cooldown).set(key, "1", ttl);
    }
    @Test void technicalHistoryBackfillRejectsNonAdminBeforeThePort() {
        var owner = mock(com.steven.assets.security.CurrentUserContext.class);
        var remote = mock(TechnicalHistoryBackfillPort.class);
        var service = new TradingRadarTechnicalBackfillService(owner, remote);
        org.assertj.core.api.Assertions.assertThatThrownBy(service::start)
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get("fixture-job"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(remote);
    }

    @Test void technicalHistoryBackfillPreservesAdminStartAndExactJobSelector() {
        var owner = mock(com.steven.assets.security.CurrentUserContext.class);
        when(owner.hasUser()).thenReturn(true);
        when(owner.isAdmin()).thenReturn(true);
        var remote = mock(TechnicalHistoryBackfillPort.class);
        var job = mock(com.steven.assets.dto.TradingRadarTechnicalBackfillJobDto.class);
        when(job.jobId()).thenReturn("fixture-job");
        when(job.createdAt()).thenReturn(java.time.OffsetDateTime.now().toString());
        when(remote.start()).thenReturn(job);
        when(remote.get("fixture-job")).thenReturn(job);
        var service = new TradingRadarTechnicalBackfillService(owner, remote);
        assertThat(service.start()).isSameAs(job);
        assertThat(service.get("fixture-job")).isSameAs(job);
        verify(remote).start();
        verify(remote).get("fixture-job");
    }
}
