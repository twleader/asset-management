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

    /**
     * 查詢股票名稱：本地 {@code stock} 主檔優先 → 查無時打外部行情（台股 FinMind / 美股‧英股 Yahoo）
     * → 查到即 {@link #upsert} 回主檔（首次寫入順帶背景觸發 10 年歷史回補）。回傳空字串代表查無。
     * 0000＝台股大盤特例直接回「台股大盤」（不打外部、不寫主檔）；美股/英股無 0000 代號回空字串
     * （避免 Yahoo fuzzy match 回隨機公司後污染主檔）。
     *
     * <p>刻意「不」加 {@code @Transactional}：外部 HTTP fetch 期間不佔用 DB 連線；{@link #upsert} 自帶交易。
     */
    public String resolveName(String code, String market) {
        String upperCode = code.trim().toUpperCase();
        if ("0000".equals(upperCode) && "台股".equals(market)) return "台股大盤";
        if ("0000".equals(upperCode) && ("美股".equals(market) || "英股".equals(market))) return "";

        String name = stockMasterRepo.findByCodeAndMarket(upperCode, market)
                .map(s -> s.getName())
                .orElse("");
        if (name.isEmpty()) {
            name = fetchExternalName(upperCode, market);
            // 查到後存入主檔，下次直接用本地（新標的順帶背景觸發 10 年歷史回補）
            if (!name.isEmpty()) {
                upsert(upperCode, market, name);
            }
        }
        return name;
    }

    /**
     * <b>純本地</b>的股票名稱解析：{@code 0000}＋台股 →「台股大盤」；否則查本地 {@code stock} 主檔；
     * <b>查無回代號本身</b>。不打外部行情 API、不寫主檔、不觸發歷史回補。
     *
     * <p><b>與 {@link #resolveName} 是兩件事，不可混用</b>（Task 254）：那一支查無主檔時會打
     * FinMind／Yahoo、把結果 upsert 回主檔並背景排 10 年回補，查無最終回<b>空字串</b>。
     * 警示觸發匯出跑在 Redis 訂閱者執行緒上（{@code PriceStreamService.onPriceUpdate} 對
     * {@code checkAlertsFor} 是同步呼叫），在那條路徑上打外部 HTTP 會拖住整條即時價管線；
     * 而 email digest 顯示「查無 → 代號本身」也比顯示空字串合理。
     *
     * <p>本方法由 {@code AlertNotificationDispatcher}（email 內的股名）與
     * {@code StockAlertTriggerExportService}（JSON 的 {@code stockName}）共用——同一個顯示欄位
     * 只能有一份來源，兩份必然分歧。
     */
    public String resolveNameLocalOnly(String code, String market) {
        if ("0000".equals(code) && "台股".equals(market)) {
            return "台股大盤";
        }
        return stockMasterRepo.findByCodeAndMarket(code, market)
                .map(s -> s.getName())
                .orElse(code);
    }

    /**
     * 反向查找：依股名精確匹配回股票代號（只查本地主檔，不打外部——FinMind/Yahoo 為 code→name 設計，反查不可靠）。
     * 空名稱回空字串；「台股大盤」＋台股特例回 0000；查無回空字串。
     */
    public String resolveCode(String name, String market) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return "";
        if ("台股大盤".equals(trimmed) && "台股".equals(market)) return "0000";
        return stockMasterRepo.findFirstByNameAndMarketOrderByCodeAsc(trimmed, market)
                .map(s -> s.getCode())
                .orElse("");
    }

    /** market → 外部股名補齊分派（台股 FinMind、英股/美股 Yahoo）。 */
    private String fetchExternalName(String code, String market) {
        if ("台股".equals(market)) return historicalDataService.fetchTwStockName(code);
        if ("英股".equals(market)) return historicalDataService.fetchUkStockName(code);
        return historicalDataService.fetchUsStockName(code);
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
