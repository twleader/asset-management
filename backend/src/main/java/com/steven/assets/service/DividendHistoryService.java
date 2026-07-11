package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.service.MarketDataService.DividendHistoryResult;
import com.steven.assets.service.MarketDataService.DividendRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 股利歷史只讀 service。
 *
 * 由 external-materials-service 每日 17:00 TW cron 抓 FinMind / NASDAQ 寫入 stock_dividend_history；
 * 此處對外提供 DB 讀取。DB 沒資料時，呼叫 external-materials-service 的 /internal/dividend/sync
 * 同步觸發抓取（供 cold-cache fallback）。
 */
@Slf4j
@Service
public class DividendHistoryService {

    private static final int RETAIN_YEARS = 10;

    private final StockDividendHistoryRepository repo;
    private final WebClient externalClient;

    public DividendHistoryService(StockDividendHistoryRepository repo,
                                  @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.repo = repo;
        this.externalClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    /** 從 DB 讀股利歷史；DB 沒資料時 fallback 觸發 external-materials-service 同步。 */
    @Transactional
    public DividendHistoryResult findFromDb(String code, String market, int years) {
        int sinceYear = Year.now().getValue() - years;
        List<StockDividendHistory> rows = repo.findByStockSinceYear(code, market, sinceYear);
        if (rows.isEmpty()) {
            // cold cache：請 external-materials-service 抓一次，等回應後重新讀 DB
            try {
                externalClient.post()
                        .uri(uri -> uri.path("/internal/dividend/sync")
                                .queryParam("code", code)
                                .queryParam("market", market).build())
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                rows = repo.findByStockSinceYear(code, market, sinceYear);
            } catch (Exception e) {
                log.warn("觸發 external-materials-service /internal/dividend/sync 失敗: {}", e.getMessage());
            }
            if (rows.isEmpty()) {
                return new DividendHistoryResult(code, market, null, "查無資料", List.of());
            }
        }
        List<DividendRow> out = new ArrayList<>(rows.size());
        for (StockDividendHistory h : rows) {
            out.add(new DividendRow(
                    h.getYear(),
                    h.getCashDividend(),
                    h.getStockDividend(),
                    h.getExDividendDate() != null ? h.getExDividendDate().toString() : null,
                    h.getYieldPct(),
                    h.getCashPaymentDate() != null ? h.getCashPaymentDate().toString() : null,
                    h.getStockPaymentDate() != null ? h.getStockPaymentDate().toString() : null,
                    h.getFillDays(),
                    h.getPreviousClose()
            ));
        }
        out.sort(Comparator
                .comparing(DividendRow::year, Comparator.reverseOrder())
                .thenComparing(r -> r.exDividendDate() == null ? "" : r.exDividendDate(), Comparator.reverseOrder()));
        String source = rows.get(0).getSource();
        return new DividendHistoryResult(code, market, source, null, out);
    }

    /**
     * 純讀股利歷史（績效比較頁 Requirement 33）：與 {@link #findFromDb} 同樣讀 stock_dividend_history、
     * 產出同樣的 DividendRow 清單，但**移除 cold-cache sync 副作用**——DB 沒資料就回空清單，
     * 絕不觸發 external-materials-service /internal/dividend/sync 寫入。故意不加 @Transactional / readOnly。
     */
    public DividendHistoryResult findFromDbReadOnly(String code, String market, int years) {
        int sinceYear = Year.now().getValue() - years;
        List<StockDividendHistory> rows = repo.findByStockSinceYear(code, market, sinceYear);
        if (rows.isEmpty()) {
            return new DividendHistoryResult(code, market, null, "查無資料", List.of());
        }
        List<DividendRow> out = new ArrayList<>(rows.size());
        for (StockDividendHistory h : rows) {
            out.add(new DividendRow(
                    h.getYear(),
                    h.getCashDividend(),
                    h.getStockDividend(),
                    h.getExDividendDate() != null ? h.getExDividendDate().toString() : null,
                    h.getYieldPct(),
                    h.getCashPaymentDate() != null ? h.getCashPaymentDate().toString() : null,
                    h.getStockPaymentDate() != null ? h.getStockPaymentDate().toString() : null,
                    h.getFillDays(),
                    h.getPreviousClose()
            ));
        }
        out.sort(Comparator
                .comparing(DividendRow::year, Comparator.reverseOrder())
                .thenComparing(r -> r.exDividendDate() == null ? "" : r.exDividendDate(), Comparator.reverseOrder()));
        String source = rows.get(0).getSource();
        return new DividendHistoryResult(code, market, source, null, out);
    }
}
