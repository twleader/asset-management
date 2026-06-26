package com.steven.assets.service;

import com.steven.assets.repository.StockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code stock} 主檔（被觀察 / 持有 / 警示標的的聯集）的唯一寫入入口。
 *
 * <p>原本各路徑各自呼叫 {@link StockRepository#upsert}（{@code StockAlertService.create/update}、
 * {@code AssetService.createSnapshot/updateSnapshot}、{@code StockAlertController.lookupName}），
 * 現統一改走本 service，集中「新標的 → 觸發 10 年歷史回補」的副作用。
 *
 * <p><b>動機（Task 126 / Requirement 7）：</b>{@code HistoricalBackfillService.startupBackfill}
 * 只在「服務啟動」掃描 {@code stock} 主檔回補歷史，兩次重啟之間新增的標的（例 2026-06 新增英股 IB01）
 * 會落入「即時價有、走勢圖歷史只有今日一格」的空窗。本 service 在主檔首次寫入該 (code, market) 時，
 * 即時於背景觸發一次回補，補上這段空窗。
 */
@Service
@Slf4j
public class StockMasterService {

    private final StockRepository stockMasterRepo;
    private final HistoricalDataService historicalDataService;

    /**
     * 新標的回補佇列：單執行緒序列化執行。
     * 用 daemon 單執行緒而非每檔開虛擬執行緒，是為了在一次大量匯入（Excel 整包新組合）時，
     * 把多檔回補排隊逐一打 ext-materials / Yahoo，避免並發觸發 Yahoo 429（與 startupBackfill
     * 逐檔 sleep 的精神一致）。
     */
    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "new-stock-backfill");
        t.setDaemon(true);
        return t;
    });

    public StockMasterService(StockRepository stockMasterRepo, HistoricalDataService historicalDataService) {
        this.stockMasterRepo = stockMasterRepo;
        this.historicalDataService = historicalDataService;
    }

    /**
     * 寫入 / 更新 {@code stock} 主檔。若該 (code, market) 先前不存在（首次被追蹤），
     * 背景觸發一次 10 年歷史回補；既有標的的名稱更新則不重複回補。
     */
    @Transactional
    public void upsert(String code, String market, String name) {
        boolean isNew = !stockMasterRepo.existsByCodeAndMarket(code, market);
        stockMasterRepo.upsert(code, market, name);
        if (isNew && !isTaiex(code, market)) {
            scheduleBackfill(code, market);
        }
    }

    /** 背景觸發單檔 10 年歷史回補（proxy 至 ext-materials；今日列仍獨佔給 ClosePersister）。 */
    private void scheduleBackfill(String code, String market) {
        final LocalDate since = LocalDate.now().minusYears(10);
        backfillExecutor.submit(() -> {
            try {
                Map<String, Object> r = historicalDataService.backfillSingleStock(code, market, since);
                log.info("新增標的 {} ({}) 自動回補 10 年歷史完成：{} 筆", code, market, r.get("records"));
            } catch (Exception e) {
                log.warn("新增標的 {} ({}) 自動回補失敗（下次服務重啟 startupBackfill 會補救）：{}",
                        code, market, e.getMessage());
            }
        });
    }

    /** 台股大盤 0000 不是真股票：歷史走 twse_index_daily_history，不回補 stock_price_history。 */
    private static boolean isTaiex(String code, String market) {
        return "0000".equals(code) && "台股".equals(market);
    }
}
