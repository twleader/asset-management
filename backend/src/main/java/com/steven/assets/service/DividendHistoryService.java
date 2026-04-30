package com.steven.assets.service;

import com.steven.assets.model.Stock;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.service.MarketDataService.DividendHistoryResult;
import com.steven.assets.service.MarketDataService.DividendRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 股利歷史快取：
 * - 每日 17:00 TW（在 price-service 16:00 close 之後）由 cron 從 FinMind 抓所有持股／觀察股利
 *   寫入 stock_dividend_history
 * - 對外查詢 (`MarketDataController /dividends`) 僅讀 DB；DB 空才一次性 fallback 抓
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendHistoryService {

    private static final int RETAIN_YEARS = 10;

    private final StockDividendHistoryRepository repo;
    private final StockRepository stockRepo;
    private final MarketDataService marketData;

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        new Thread(() -> {
            try {
                int synced = 0;
                for (Stock s : stockRepo.findAll()) {
                    if (repo.countByStockCodeAndMarket(s.getCode(), s.getMarket()) == 0) {
                        try {
                            syncOne(s.getCode(), s.getMarket());
                            synced++;
                            Thread.sleep(500);
                        } catch (Exception e) {
                            log.warn("dividend warmup {} {} 失敗: {}", s.getMarket(), s.getCode(), e.getMessage());
                        }
                    }
                }
                if (synced > 0) {
                    log.info("dividend warmup 完成：補齊 {} 檔", synced);
                }
            } catch (Exception e) {
                log.warn("dividend warmup error: {}", e.getMessage());
            }
        }, "dividend-warmup").start();
    }

    /** 每日 17:00 TW 全量同步。 */
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledSyncAll() {
        log.info("排程：同步股利歷史");
        int ok = 0, fail = 0;
        for (Stock s : stockRepo.findAll()) {
            try {
                syncOne(s.getCode(), s.getMarket());
                ok++;
                Thread.sleep(300);
            } catch (Exception e) {
                fail++;
                log.warn("dividend sync {} {} 失敗: {}", s.getMarket(), s.getCode(), e.getMessage());
            }
        }
        log.info("股利歷史同步完成：成功 {} 檔，失敗 {} 檔", ok, fail);
    }

    /** 由外部呼叫（控制器 fallback、UI 主動 refresh）。 */
    @Transactional
    public DividendHistoryResult syncOne(String code, String market) {
        DividendHistoryResult res = marketData.getDividendHistory(code, market, RETAIN_YEARS);
        if (res == null || res.rows() == null || res.rows().isEmpty()) {
            return res != null ? res : new DividendHistoryResult(code, market, null, "查無資料", List.of());
        }
        for (DividendRow row : res.rows()) {
            if (row.year() == null) continue;
            LocalDate ex = parseDate(row.exDividendDate());
            Optional<StockDividendHistory> existing = ex != null
                    ? repo.findFirstByStockCodeAndMarketAndYearAndExDividendDate(code, market, row.year(), ex)
                    : repo.findFirstByStockCodeAndMarketAndYearAndExDividendDateIsNull(code, market, row.year());
            StockDividendHistory entity = existing.orElseGet(() -> StockDividendHistory.builder()
                    .stockCode(code).market(market).year(row.year()).build());
            entity.setCashDividend(row.cashDividend());
            entity.setStockDividend(row.stockDividend());
            entity.setExDividendDate(ex);
            entity.setYieldPct(row.yieldPct());
            entity.setCashPaymentDate(parseDate(row.cashPaymentDate()));
            entity.setStockPaymentDate(parseDate(row.stockPaymentDate()));
            entity.setFillDays(row.fillDays());
            entity.setPreviousClose(row.previousClose());
            entity.setSource(res.source());
            entity.setUpdatedAt(LocalDateTime.now());
            repo.save(entity);
        }
        return res;
    }

    /** 從 DB 讀股利歷史；DB 沒資料時 fallback 即時抓並寫入。 */
    @Transactional
    public DividendHistoryResult findFromDb(String code, String market, int years) {
        int sinceYear = Year.now().getValue() - years;
        List<StockDividendHistory> rows = repo.findByStockSinceYear(code, market, sinceYear);
        if (rows.isEmpty()) {
            // cold cache：抓一次 + 寫 DB
            DividendHistoryResult fresh = syncOne(code, market);
            if (fresh != null && fresh.rows() != null && !fresh.rows().isEmpty()) {
                rows = repo.findByStockSinceYear(code, market, sinceYear);
            } else {
                return fresh;
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
        // 排序：年份 desc，同年除息日 desc nulls last
        out.sort(Comparator
                .comparing(DividendRow::year, Comparator.reverseOrder())
                .thenComparing(r -> r.exDividendDate() == null ? "" : r.exDividendDate(), Comparator.reverseOrder()));
        String source = rows.get(0).getSource();
        return new DividendHistoryResult(code, market, source, null, out);
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }
}
