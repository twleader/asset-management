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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 股利歷史只讀 service。
 *
 * external-materials-service 每日 17:00 TW cron 只追加 immutable dividend evidence；
 * backend 在讀取前把最新、完整且涵蓋未來 45 日的 snapshot 投影至 stock_dividend_history。
 * DB 沒資料時，呼叫 external-materials-service 的 /internal/dividend/sync 同步觸發抓取
 * （供 cold-cache fallback），再由 backend 重做一次投影。
 */
@Slf4j
@Service
public class DividendHistoryService {

    private static final int RETAIN_YEARS = 10;

    private final StockDividendHistoryRepository repo;
    private final DividendCurrentStateProjectionService projectionService;
    private final WebClient externalClient;

    @org.springframework.beans.factory.annotation.Autowired
    private DividendCashEnrichmentService enrichmentService;

    public DividendHistoryService(StockDividendHistoryRepository repo,
                                  DividendCurrentStateProjectionService projectionService,
                                  @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.repo = repo;
        this.projectionService = projectionService;
        this.externalClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    /** 從 DB 讀股利歷史；DB 沒資料時 fallback 觸發 external-materials-service 同步。 */
    @Transactional
    public DividendHistoryResult findFromDb(String code, String market, int years) {
        projectFailSoft(code, market);
        int sinceYear = Year.now().getValue() - years;
        List<StockDividendHistory> rows = repo.findByStockSinceYear(code, market, sinceYear);
        if (rows.isEmpty()) {
            // cold cache：請 external-materials-service 抓一次，等回應後重新讀 DB
            boolean synced = false;
            try {
                externalClient.post()
                        .uri(uri -> uri.path("/internal/dividend/sync")
                                .queryParam("code", code)
                                .queryParam("market", market).build())
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                synced = true;
            } catch (RuntimeException e) {
                log.warn("觸發 external-materials-service /internal/dividend/sync 失敗: {}", e.getMessage());
            }
            if (synced) {
                projectFailSoft(code, market);
                rows = repo.findByStockSinceYear(code, market, sinceYear);
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
                    h.getPreviousClose(),
                    h.getExRightsDate() != null ? h.getExRightsDate().toString() : null
            ));
        }
        // Task 357：次要排序鍵改用 anchorDate，否則純配股事件（exDividendDate 為
        // null）排序退化成空字串、被推到同年度所有列的最前面（顯示順序瑕疵）。
        out.sort(Comparator
                .comparing(DividendRow::year, Comparator.reverseOrder())
                .thenComparing(r -> anchorDate(r) == null ? "" : anchorDate(r), Comparator.reverseOrder()));
        String source = rows.get(0).getSource();
        return new DividendHistoryResult(code, market, source, null, out);
    }

    /** anchorDate = exDividendDate ?? exRightsDate（Task 357），皆為 ISO 字串或 null。 */
    private static String anchorDate(DividendRow row) {
        return row.exDividendDate() != null ? row.exDividendDate() : row.exRightsDate();
    }

    private void projectFailSoft(String code, String market) {
        try {
            projectionService.projectOne(code, market, Instant.now());
        } catch (RuntimeException e) {
            log.warn("股利 current-state 投影失敗：{} {}: {}", market, code, e.getMessage());
        }
        try {
            if (enrichmentService != null) enrichmentService.enrich(code, market, Instant.now());
        } catch (RuntimeException e) {
            log.warn("股利現金資訊回填失敗：{} {}: {}", market, code, e.getMessage());
        }
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
                    h.getPreviousClose(),
                    h.getExRightsDate() != null ? h.getExRightsDate().toString() : null
            ));
        }
        // Task 357：次要排序鍵改用 anchorDate，否則純配股事件（exDividendDate 為
        // null）排序退化成空字串、被推到同年度所有列的最前面（顯示順序瑕疵）。
        out.sort(Comparator
                .comparing(DividendRow::year, Comparator.reverseOrder())
                .thenComparing(r -> anchorDate(r) == null ? "" : anchorDate(r), Comparator.reverseOrder()));
        String source = rows.get(0).getSource();
        return new DividendHistoryResult(code, market, source, null, out);
    }
}
