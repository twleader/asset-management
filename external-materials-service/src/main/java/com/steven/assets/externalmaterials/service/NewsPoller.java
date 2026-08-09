package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.NewsFetchClient;
import com.steven.assets.externalmaterials.client.NewsRow;
import com.steven.assets.externalmaterials.client.TwseInfoFetchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地財經新聞抓取排程（Task 149.21）：抓權威新聞（玩股網 / MoneyDJ / 自由時報財經・政治・國際 / 經濟日報）
 * ＋證交所公開資訊（三大法人、大盤成交）＋量化快照（台幣兌美元匯率、美股主要指數收盤 Task 180；
 * 韓國股市 KOSPI＋三星/海力士 Task 185；韓股**盤中**開盤動向 Task 193，僅韓股盤中時段＝台北 08:00~14:30
 * 產出，收盤後的輪次自然為空），去重後 upsert 至 news_headline，供 business-services 的
 * 今日股市分析餵入 prompt。來源皆台/美權威網站，不抓中港澳。
 *
 * <p><b>執行時間（Requirement 38 / Task 192）</b>：原寫死三個 cron（{@code 0 20 8}／{@code 0 30 11}／{@code 0 0 18}），
 * 改為 DB 驅動——每分鐘 ticker 讀 {@code crawler_schedule}（{@code crawler_key='news-poller'}）已啟用時間點，
 * 命中當前 {@code HH:mm} 即抓取。時間點由「爬蟲資訊查詢」頁設定、可多個、免重啟生效。預設 seed 08:20 / 11:30 / 18:00
 * ＝原寫死行為（早上 06:00→08:00 Task 177→08:20 Task 184；中午 12:00→11:30 Task 188；**08:20 那次早於 08:45
 * 今日股市分析**，確保當日有料）。DB 讀取例外時 fallback 至同一組預設值。
 *
 * <p>每輪抓取（含開機 warmup）<b>先 upsert news_headline，再由 DB 查詢「當日公開資訊」</b>輸出一份 JSON 至
 * SRPP 退休規劃專案輸入目錄（Task 177，DB 為單一來源），供其量化分析取用；寫檔失敗 graceful，不影響落庫。
 *
 * <p><b>輸出目錄（Requirement 38 / Task 212）</b>：原寫死 {@code news-scraper.export-dir}（容器 {@code /srpp-input}，
 * 改目的地必須改 docker volume 重新部署），改為 DB 驅動——每輪寫檔前讀 {@code crawler_export_setting}
 * （{@code crawler_key='news-poller'}）的相對子路徑，實際目錄 = 容器基底 {@code EXPORT_OUTPUT_DIR}
 * （預設 {@code /home/steven}，volume 對映 host 家目錄）resolve 之。目錄由「爬蟲資訊查詢」頁設定、免重啟生效
 * （不快取於欄位，下一輪即讀到新值）。DB 例外／空值／跳脫基底時 fallback 至 {@link #DEFAULT_EXPORT_SUBPATH}
 * ＝改為 DB 驅動前 {@code /srpp-input} 的同一個 host 目錄。
 *
 * <p><b>手動觸發（Requirement 63 / Task 280）</b>：除排程與 warmup 外，另有兩個手動入口，
 * 由「爬蟲資訊查詢」頁的兩顆按鈕經 BFF → business proxy 至 ext：
 * <ul>
 *   <li>{@link #exportNow()}（「立即匯出」）——<b>只重產檔案</b>：不抓取、不寫 {@code news_headline}，
 *       直接走 {@link #exportPublicInfoJson(String)}，{@code trigger} 標為 {@code manual-export}。</li>
 *   <li>{@link #fetchAndExportNow()}（「立即抓取並匯出」）——<b>完整跑一輪</b>：走同一段
 *       {@link #run(String)}，{@code trigger} 標為 {@code manual}。</li>
 * </ul>
 * 三條途徑（排程／warmup／手動）共用同一段程式碼、只有 {@code trigger} 標籤不同——各寫一份的話，
 * 抓取來源清單、個股過濾、cutoff 規則、雙格式產出、Drive 同步這五處遲早漂移。兩個手動入口都必須
 * 取得同一個 {@link #running} 旗標（取不到即回 {@code BUSY}、不排隊、不啟第二輪）。
 *
 * <p>開機 warmup（{@link ApplicationReadyEvent}）先跑一次，部署後立即有資料。每次末尾清理保留期外舊聞。
 * 逐來源／逐則 graceful：任一失敗只 log warn、不影響其他，比照既有 producer 慣例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsPoller {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");

    /** 排程設定的爬蟲代號（Requirement 38）。 */
    private static final String CRAWLER_KEY = "news-poller";

    /** DB 讀取失敗時的 fallback 執行時間（等同原寫死 08:20 / 11:30 / 18:00），避免爬蟲靜默停擺（Requirement 38）。 */
    private static final int[][] DEFAULT_TIMES = {{8, 20}, {11, 30}, {18, 0}};

    /**
     * DB 未設定／讀取失敗／值跳脫基底時的 fallback 輸出子路徑（Task 212）。相對 {@code EXPORT_OUTPUT_DIR}
     * 解析後 = host {@code /Users/steven/Project/SRPP/data/input}，即 Task 212 之前 {@code /srpp-input} 的同一個目錄。
     */
    private static final String DEFAULT_EXPORT_SUBPATH = "Project/SRPP/data/input";

    private final NewsFetchClient newsClient;
    private final TwseInfoFetchClient twseClient;
    private final MarketSnapshotFetchClient snapshotClient;
    private final KrIntradayFetchClient krIntradayClient;
    private final MaCrossSnapshotClient maCrossClient;
    private final StockSourceQuery source;
    private final PublicInfoStockFilter stockFilter;
    private final MarketCalendar calendar;
    private final CrawlerScheduleQuery scheduleQuery;
    private final CrawlerExportPathQuery exportPathQuery;
    private final GdriveUploader gdriveUploader;
    private final ObjectMapper objectMapper;
    // 公開資訊的 Excel 那一份（Requirement 55 / Task 272）：吃 JSON 已組好的同一份 payload，不重查
    private final PublicInfoXlsxWriter xlsxWriter;

    /** 防止上一輪抓取尚未結束又被下一分鐘 ticker 重複觸發（抓取可能耗數十秒）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 手動觸發的模式標籤（Requirement 63 / Task 280）：回應要能自證是哪一顆按鈕的結果。 */
    private static final String MODE_FETCH_AND_EXPORT = "FETCH_AND_EXPORT";
    private static final String MODE_EXPORT_ONLY = "EXPORT_ONLY";

    /**
     * 一輪輸出的結果分類（Requirement 63 / Task 280）。
     *
     * <p><b>必須是結構化的分類，不得靠比對訊息字串分流</b>：{@code export-enabled=false} 早退與寫檔失敗
     * 產生的 {@link ExportOutcome} <b>形狀完全相同</b>（{@code jsonPath == null} ＋ 一段中文 message），
     * 而這兩者在前端一個是灰色提示、一個是紅色錯誤。
     */
    enum ExportStatus {
        /** 本機 JSON 那一份確實寫成功（xlsx 與 Drive 可能仍失敗，另由各自欄位表達）。 */
        OK,
        /** 跑了，但本機 JSON 寫檔失敗（輸出目錄不可寫、磁碟滿等）。 */
        FAILED,
        /** {@code news-scraper.export-enabled=false}，產檔那一步早退。 */
        DISABLED
    }

    /** 一輪輸出的結果（Task 280）。除 {@code outcome} 外欄位為 null＝該步驟沒做或失敗，皆為既有的 graceful 行為。 */
    record ExportOutcome(ExportStatus outcome, String jsonPath, Long jsonSizeBytes,
                         String xlsxPath, Long xlsxSizeBytes, Integer exported,
                         String jsonGdrivePath, String xlsxGdrivePath, String gdriveStatus,
                         String message) {}

    /** Drive 同步結果（Task 280）；未啟用（或連設定都讀不到）時三欄皆為 null。 */
    record GdriveOutcome(String jsonPath, String xlsxPath, String status) {}

    /**
     * 手動觸發一輪的結果（Requirement 63 / Task 280），由 ext 的兩支 {@code /internal/news-poller/*}
     * 端點回傳、business 端原樣 proxy 給前端。
     *
     * <p>{@code status}：{@code OK}（跑完了，且本機 JSON 那一份確實寫成功）／{@code FAILED}（跑了但本機
     * JSON 寫檔失敗）／{@code BUSY}（上一輪尚未結束、本次未啟動）／{@code DISABLED}（功能被關閉）。
     * <b>{@code RUNNING}／{@code ERROR} 由 business 端於逾時／連線失敗時合成，ext 不產生這兩個值。</b>
     *
     * <p>排程輪與 warmup 不看這個回傳值，行為完全不受影響。
     *
     * @param mode      {@code FETCH_AND_EXPORT}／{@code EXPORT_ONLY}
     * @param upserted  只有 {@code FETCH_AND_EXPORT} 有值；{@code EXPORT_ONLY} 一律 null
     *                  （不是 {@code 0}——那會被讀成「抓了但一筆都沒進」，與「根本沒抓」是不同的事）
     * @param failed    同上
     */
    public record ManualRunResult(
            String status,
            String mode,
            String jsonPath,
            Long jsonSizeBytes,
            String xlsxPath,
            Long xlsxSizeBytes,
            Integer upserted,
            Integer failed,
            Integer exported,
            String jsonGdrivePath,
            String xlsxGdrivePath,
            String gdriveStatus,
            String message
    ) {}

    @Value("${news-scraper.enabled:true}")
    private boolean enabled;

    @Value("${news-scraper.retention-days:30}")
    private int retentionDays;

    /**
     * 公開資訊 JSON 輸出的容器內**基底**目錄（Task 212）：docker volume 對映 host 家目錄，與 business-services
     * 的排程匯出共用同一基底與同一份掛載——前端資料夾樹（由 business 列舉）看得到的目錄才等於爬蟲寫得到的目錄。
     * 實際輸出目錄 = 本基底 resolve {@code crawler_export_setting} 的相對子路徑。
     */
    @Value("${EXPORT_OUTPUT_DIR:/home/steven}")
    private String exportBaseDir;

    /** 公開資訊 JSON 輸出開關（Task 177）。 */
    @Value("${news-scraper.export-enabled:true}")
    private boolean exportEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        if (!enabled) {
            log.info("本地新聞爬蟲已停用（news-scraper.enabled=false），略過 warmup");
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                runGuarded("warmup");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("本地新聞 warmup 失敗：{}", e.getMessage());
            }
        }, "news-warmup").start();
    }

    /**
     * 每分鐘 ticker（Requirement 38 / Task 192）：讀 {@code crawler_schedule} 中本爬蟲「已啟用」的執行時間點，
     * 命中當前 {@code HH:mm}（Asia/Taipei）即抓取一次。時間點由「爬蟲資訊查詢」頁設定、可多個、免重啟即生效
     * （下一分鐘 ticker 讀到新值），取代原寫死的三個 cron（08:20 / 11:30 / 18:00，Task 184／188）。cron 每分鐘
     * 僅觸發一次故天然去重、無需額外 slot guard；抓取以 dedupe_key upsert 本就冪等、重跑無害。抓取在**獨立執行緒**
     * 進行，避免阻塞 ext 共用的單執行緒排程器（其他 poller 亦共用）。讀到「空清單」＝使用者刻意清空＝該分鐘不跑；
     * DB 讀取例外才 fallback 至預設 08:20 / 11:30 / 18:00（避免靜默停擺）。
     */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void tick() {
        if (!enabled) return;
        LocalTime now = LocalTime.now(TW_ZONE);
        if (!matchesConfiguredTime(now.getHour(), now.getMinute())) return;
        new Thread(() -> runGuarded("scheduled"), "news-scheduled").start();
    }

    /** 現在時分是否命中已設定（啟用）的執行時間點；DB 例外時 fallback 至預設 08:20 / 11:30 / 18:00。 */
    private boolean matchesConfiguredTime(int hour, int minute) {
        List<int[]> times;
        try {
            times = scheduleQuery.enabledTimes(CRAWLER_KEY);
        } catch (Exception e) {
            log.warn("讀爬蟲排程設定失敗，本分鐘以預設 08:20 / 11:30 / 18:00 判斷：{}", e.getMessage());
            times = new ArrayList<>();
            for (int[] t : DEFAULT_TIMES) times.add(t);
        }
        for (int[] t : times) {
            if (t[0] == hour && t[1] == minute) return true;
        }
        return false;
    }

    /** 以 running 旗標防重疊地執行一次抓取（warmup 與每分鐘 ticker 共用）。 */
    private void runGuarded(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("本地新聞抓取（{}）略過：上一輪尚未結束", trigger);
            return;
        }
        try {
            run(trigger);   // 排程輪與 warmup 忽略回傳值，行為與 Task 280 之前完全相同
        } finally {
            running.set(false);
        }
    }

    /**
     * 手動「立即抓取並匯出」（Requirement 63 / Task 280）：完整跑一輪，與排程輪、warmup 走
     * <b>同一段</b> {@link #run(String)}，只有 {@code trigger} 標籤不同（{@code manual}）。
     *
     * <p><b>方法名刻意不叫 {@code runNow()}</b>：business 端叫 {@code runNow(...)} 的是「只重產檔案」
     * （對應 {@code POST /api/crawler-export-path/run-now}，沿用全庫既有八個 run-now「不重新抓資料」的語意）。
     * 同一字串在兩層反義時，接反是<b>靜默的</b>——兩支都產出同名的兩份檔，只差有沒有抓。
     *
     * <p><b>自己做 {@code compareAndSet} 而不改寫 {@link #runGuarded}</b>：後者是 warmup 與排程輪的入口，
     * 它「取不到旗標就只 log 並 return」的行為必須原封不動；手動輪則需要把「沒跑」這件事<b>回報給使用者</b>。
     * 三者共用同一個 {@link #running} 欄位即達成互斥。
     */
    public ManualRunResult fetchAndExportNow() {
        if (!enabled) {
            return busyOrDisabled("DISABLED", MODE_FETCH_AND_EXPORT,
                    "爬蟲已停用（news-scraper.enabled=false），未執行");
        }
        if (!running.compareAndSet(false, true)) {
            log.info("本地新聞抓取（manual）略過：上一輪尚未結束");
            return busyOrDisabled("BUSY", MODE_FETCH_AND_EXPORT, "上一輪抓取尚未結束，本次未啟動；請稍候再試");
        }
        try {
            return run("manual");
        } finally {
            running.set(false);
        }
    }

    /**
     * 手動「立即匯出」（Requirement 63 / Task 280）：<b>只重產檔案</b>——不抓取、不寫 {@code news_headline}，
     * 直接由 DB 現有資料走 {@link #exportPublicInfoJson(String)} 產出兩份檔案並（啟用時）同步 Drive，
     * {@code trigger} 標為 {@code manual-export}。用途是改完輸出資料夾／Drive 設定後立刻驗證落點。
     *
     * <p><b>刻意不看 {@code news-scraper.enabled}</b>：那是「要不要自動抓取」的開關，與重產檔案無關；
     * 看了會讓「爬蟲整體停用但仍想重產檔案」的情境被錯誤擋掉。{@code DISABLED} 一律由
     * {@link #exportPublicInfoJson(String)} 回傳的 {@link ExportStatus} 決定（{@code export-enabled}
     * 的判斷在該方法第一行、單一來源），<b>不在這裡複製一份判斷、也不比對訊息字串</b>。
     *
     * <p><b>同樣要取得 {@link #running}</b>：它與排程輪寫的是<b>同一組檔名</b>，共用同一個閘門才不會出現
     * 「排程輪寫到一半、手動輪同時覆寫」的交錯情境。持有時間極短（不抓取），Drive 啟用時上限為兩份各 45 秒。
     */
    public ManualRunResult exportNow() {
        if (!running.compareAndSet(false, true)) {
            log.info("公開資訊輸出（manual-export）略過：上一輪尚未結束");
            return busyOrDisabled("BUSY", MODE_EXPORT_ONLY, "上一輪抓取尚未結束，本次未啟動；請稍候再試");
        }
        ExportOutcome out;
        try {
            out = exportPublicInfoJson("manual-export");
        } finally {
            running.set(false);
        }
        // upserted／failed 一律 null：這條路徑根本沒抓，填 0 會被讀成「抓了但一筆都沒進」
        return new ManualRunResult(out.outcome().name(), MODE_EXPORT_ONLY,
                out.jsonPath(), out.jsonSizeBytes(), out.xlsxPath(), out.xlsxSizeBytes(),
                null, null, out.exported(),
                out.jsonGdrivePath(), out.xlsxGdrivePath(), out.gdriveStatus(), out.message());
    }

    /** 「沒跑」的兩種結果（{@code BUSY}／{@code DISABLED}）：除 status／mode／message 外一律 null。 */
    private static ManualRunResult busyOrDisabled(String status, String mode, String message) {
        return new ManualRunResult(status, mode, null, null, null, null,
                null, null, null, null, null, null, message);
    }

    private ManualRunResult run(String trigger) {
        List<NewsRow> rows = new ArrayList<>();
        rows.addAll(newsClient.fetchAll());
        TwseInfoFetchClient.FetchResult twseResult = twseClient.fetchAllTyped();
        if (twseResult != null) {
            rows.addAll(twseResult.newsRows());
            if (twseResult.institutionalObservation() != null) {
                try {
                    // Typed numeric source only. Never parse the NewsRow projection back into values.
                    source.appendTwseInstitutionalObservation(
                            twseResult.institutionalObservation());
                } catch (RuntimeException e) {
                    log.warn("TWSE 三大法人 observation 寫入失敗：{}", e.getMessage());
                }
            }
        }
        // Task 180：台幣兌美元匯率＋美股主要指數收盤快照（由 DB 既有資料組裝，供 SRPP JSON 與今日股市分析）。
        rows.addAll(snapshotClient.fetchAll());
        // Task 193：韓股盤中快照（即時抓 Yahoo；僅韓股盤中時段＝台北 08:00~14:30 產出，收盤後的輪次自然為空）。
        rows.addAll(krIntradayClient.fetchAll());
        // Task 207：台股均線突破快照（由 DB 日收盤算 MA60／MA240，僅在偵測到漲破／跌破時產出，平常為空）。
        rows.addAll(maCrossClient.fetchAll());

        // 個股過濾（Task 178）：只留 stock 主檔個股＋總體新聞，其餘個股濾除（DB 落庫與 JSON 輸出前套用）。
        rows = stockFilter.retain(rows);

        int ok = 0, fail = 0;
        for (NewsRow r : rows) {
            try {
                source.upsertNews(
                        trunc(r.title(), 500), trunc(r.source(), 100), trunc(r.url(), 1024),
                        r.category(), r.region(), trunc(r.summary(), 4000),
                        r.publishedAt(), dedupeKey(r));
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("本地新聞 upsert 失敗（{}｜{}）：{}", r.source(), r.title(), e.getMessage());
            }
        }

        int deleted = 0;
        try {
            deleted = source.deleteNewsOlderThan(Instant.now().minus(Duration.ofDays(Math.max(1, retentionDays))));
        } catch (Exception e) {
            log.warn("本地新聞保留期清理失敗：{}", e.getMessage());
        }
        log.info("本地新聞抓取（{}）：upsert {} 則、失敗 {}、清理過期 {} 則", trigger, ok, fail, deleted);

        ExportOutcome out = exportPublicInfoJson(trigger);
        // status 一律映自 outcome，不得寫死 OK：抓取成功但檔案寫不出去時，那一輪對使用者而言就是失敗的。
        // enabled=true 但 export-enabled=false 這一格因此有明確答案——抓取與 upsert 照跑完（不跳過），
        // 產檔早退 → DISABLED，且 upserted／failed 有值、兩個路徑為 null。
        String message = out.outcome() == ExportStatus.DISABLED
                ? "抓取已完成 " + ok + " 則，" + out.message()
                : out.message();
        return new ManualRunResult(out.outcome().name(), MODE_FETCH_AND_EXPORT,
                out.jsonPath(), out.jsonSizeBytes(), out.xlsxPath(), out.xlsxSizeBytes(),
                ok, fail, out.exported(),
                out.jsonGdrivePath(), out.xlsxGdrivePath(), out.gdriveStatus(), message);
    }

    /**
     * 由 news_headline 產生一份「當日公開資訊」JSON 至 SRPP 退休規劃專案輸入目錄（Task 177，DB 為單一來源）。
     * 範圍＝今天(Asia/Taipei)這批爬蟲抓進來的（{@code fetched_at} 為今天）、且資料日期 {@code published_at}
     * 不早於「上一交易日」的列；上一交易日＝news_headline 中 twse 總體資料的最新資料日（TWSE 權威，無則以
     * {@link MarketCalendar} 最近交易日 fallback）。如此三大法人／大盤成交（日期＝上一交易日）保留，今天抓到
     * 但發布日更舊的過期新聞則排除。輸出目錄每輪由 {@link #resolveExportDir()} 依 DB 設定決定（Task 212）；
     * 檔名 {@code public_info_<yyyy-MM-dd>.json}（同日多輪覆寫＝當日最新、跨日新檔，**檔名不開放設定**——
     * SRPP 依此檔名取用）；內容含 metadata（generatedAt／trigger／tradingDayCutoff／count）與逐則明細。
     * 寫檔失敗一律 graceful。
     */
    private ExportOutcome exportPublicInfoJson(String trigger) {
        if (!exportEnabled) {
            return new ExportOutcome(ExportStatus.DISABLED, null, null, null, null, null,
                    null, null, null, "公開資訊輸出已停用（news-scraper.export-enabled=false），未產檔");
        }
        LocalDate today = LocalDate.now(TW_ZONE);
        Path writtenFile = null;   // 本機檔寫成功才會被設值；null＝本機這一步失敗，Drive 不該上傳舊檔
        Path xlsxFile = null;      // 同上；xlsx 失敗時 JSON 那一份仍照常上傳
        Long jsonSize = null;      // Task 280：多帶一份既有資訊出去供手動輪回報，不影響任何既有分支
        Long xlsxSize = null;
        Integer exported = null;
        String failure = null;
        try {
            LocalDate cutoff = resolveTradingCutoff(today);
            List<NewsRow> items = source.loadTodayPublicInfoForExport(today, cutoff);

            Path dir = resolveExportDir();
            Files.createDirectories(dir);
            Path file = dir.resolve("public_info_" + today + ".json");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("generatedAt", ZonedDateTime.now(TW_ZONE).toString());
            payload.put("trigger", trigger);
            payload.put("tradingDayCutoff", cutoff.toString());
            payload.put("count", items.size());
            payload.put("items", items);

            // 先寫唯一暫存檔再原子 rename：避免 warmup 執行緒與 cron 併發寫同一檔造成截斷／交錯毀損，
            // 也讓 SRPP 不會讀到寫到一半的檔（Task 177 review 修正）。暫存檔與目標同目錄以確保同一檔案系統可原子搬移。
            Path tmp = Files.createTempFile(dir, "public_info_", ".json.tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), payload);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException amse) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);   // move 成功後為 no-op；writeValue 失敗時清掉殘留暫存檔
            }
            writtenFile = file;   // 本機（SRPP 的資料來源）已確定寫成功，Drive 才可以上傳這一份
            jsonSize = sizeOrNull(file);
            exported = items.size();
            log.info("公開資訊輸出 JSON（{}）：{} 筆（當日 fetched、published≥{}）→ {}",
                    trigger, items.size(), cutoff, file);

            // Excel 那一份（Requirement 55 / Task 272）：沿用**同一份 payload**，不重查。
            // 順序守門——JSON 是 SRPP 的權威來源，先確定它寫成功才寫 xlsx；反向（xlsx 失敗）
            // 只記 warn，絕不影響 JSON、絕不中斷爬取流程。
            xlsxFile = writePublicInfoXlsx(dir, today, payload, trigger);
            xlsxSize = sizeOrNull(xlsxFile);
        } catch (Exception e) {
            log.warn("公開資訊輸出 JSON 失敗（{}）：{}", trigger, e.getMessage());
            failure = "本機寫檔失敗：" + e.getMessage();
        }

        // Drive 同步刻意放在上面 try-catch **之外**：本機寫檔失敗時也要能記錄「跳過」狀態，
        // 否則設定頁會停留在上一次的「成功」，顯示過期的好消息（Requirement 50）。
        GdriveOutcome gdrive = syncToGdrive(writtenFile, xlsxFile, today, trigger);

        // outcome 依 writtenFile 決定（已排除 DISABLED——那在方法第一行就早退了）：
        // 非 null → OK；null → FAILED。不得只看「路徑是不是 null」而不分辨早退，那會把 DISABLED 併吃掉。
        return new ExportOutcome(
                writtenFile == null ? ExportStatus.FAILED : ExportStatus.OK,
                writtenFile == null ? null : writtenFile.toAbsolutePath().toString(), jsonSize,
                xlsxFile == null ? null : xlsxFile.toAbsolutePath().toString(), xlsxSize,
                exported, gdrive.jsonPath(), gdrive.xlsxPath(), gdrive.status(), failure);
    }

    /**
     * 取檔案大小，失敗回 {@code null}（Task 280）。
     *
     * <p><b>絕不讓它擲出</b>：檔案大小純粹是回報用的附加資訊，而這兩次取值都插在
     * {@link #exportPublicInfoJson} 既有的 try 內——若讓 {@code Files.size} 的 {@code IOException}
     * 逸出，JSON 那一份的取值失敗會連帶跳過 {@link #writePublicInfoXlsx}（JSON 已寫成功卻不產 xlsx，
     * 破壞 Requirement 55 的雙格式保證），xlsx 那一份的取值失敗則會讓一次其實全部成功的匯出
     * 被記成「本機寫檔失敗」。取不到大小只是少一個數字，不該改變任何控制流。
     */
    private static Long sizeOrNull(Path file) {
        if (file == null) return null;
        try {
            return Files.size(file);
        } catch (Exception e) {
            log.warn("取檔案大小失敗（不影響已寫出的檔案）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 寫出 Excel 那一份（Requirement 55 / Task 272）：主檔名與 JSON 相同、只差副檔名。
     *
     * <p><b>整段 graceful</b>：產檔或寫檔失敗只記 {@code warn} 並回 {@code null}——
     * 絕不影響已寫成功的 JSON（SRPP 的資料來源）、絕不中斷本輪爬取。
     */
    private Path writePublicInfoXlsx(Path dir, LocalDate today, Map<String, Object> payload, String trigger) {
        Path tmp = null;
        try {
            byte[] data = xlsxWriter.build(payload);
            Path file = dir.resolve("public_info_" + today + ".xlsx");
            tmp = Files.createTempFile(dir, "public_info_", ".xlsx.tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amse) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("公開資訊輸出 Excel（{}）：{}（{} bytes）", trigger, file, data.length);
            return file;
        } catch (Exception e) {
            log.warn("公開資訊輸出 Excel 失敗（{}）：{}——JSON 那一份不受影響", trigger, e.getMessage());
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // 清 tmp 失敗不影響本輪結果
                }
            }
        }
    }

    /**
     * 把本機那一份公開資訊 JSON 額外上傳一份副本到 Google Drive（Requirement 50 / Task 241）。
     *
     * <p><b>best-effort，絕不回頭影響本機。</b>本機檔案是 SRPP 退休規劃專案的資料來源，Drive 只是附加副本：
     * 這裡的任何失敗都只記 log 與 DB 狀態欄，<b>絕不</b> rollback 本機檔案、<b>絕不</b>讓本輪
     * {@code news_headline} 入庫失敗、<b>絕不</b>擲例外中斷排程。
     *
     * <p><b>不實作 retry queue</b>：爬蟲每輪都重新產生當日完整檔案並重新上傳，下一輪即為天然重試
     * （Drive 端覆寫同名檔本身冪等）。
     *
     * <p>package-private 而非 private：本方法的三條「絕不」保證是本任務風險最高的部分，必須能被單元測試
     * 直接驗證，而不必跑整個抓取流程（比照本服務其他 poller 的 {@code updateOnce()} 測試入口慣例）。
     *
     * <p>Task 280 起回傳 {@link GdriveOutcome} 供手動輪把結果回報給使用者。<b>回傳值只是把既有的
     * log／狀態欄內容多帶一份出去，上述三條「絕不」保證與所有既有分支的行為完全不變</b>——
     * 排程輪與 warmup 忽略這個回傳值。
     *
     * @param localFile 已寫成功的本機 JSON 檔；{@code null} 表示本機這一步就失敗了
     * @param xlsxFile  已寫成功的本機 Excel 檔；{@code null} 表示那一份沒產出——
     *                  <b>此時 JSON 那一份仍照常上傳</b>（JSON 是 SRPP 的契約，不能因為 Excel 壞掉就不同步）
     * @return Drive 落點與狀態；未啟用（或連設定都讀不到）時三欄皆為 {@code null}
     */
    GdriveOutcome syncToGdrive(Path localFile, Path xlsxFile, LocalDate today, String trigger) {
        CrawlerExportPathQuery.GdriveConfig cfg;
        try {
            cfg = exportPathQuery.gdriveConfig(CRAWLER_KEY);
        } catch (Exception e) {
            // 連設定都讀不到就無從得知使用者是否啟用；此時不寫狀態欄（避免在「其實沒啟用」時留下誤導訊息）
            log.warn("讀取 Drive 同步設定失敗（{}），本輪跳過上傳：{}", trigger, e.getMessage());
            return new GdriveOutcome(null, null, null);
        }
        // 未啟用：完全不呼叫 rclone，也不動狀態欄
        if (!cfg.enabled()) return new GdriveOutcome(null, null, null);

        // 以下都是「已啟用」的情境——無論成功、失敗或跳過，都必須寫狀態欄，
        // 讓 gdrive_last_run_at 恆為「最近一次判斷結果」而非「最近一次成功」。
        try {
            String skip = null;
            if (localFile == null) {
                skip = "跳過：本機檔案寫入失敗，未上傳";
            } else if (!gdriveUploader.isAvailable()) {
                skip = "跳過：rclone 設定不可用（remote " + gdriveUploader.remoteName() + " 未掛入設定檔）";
            } else if (isInvalidGdriveSubpath(cfg.subpath())) {
                // 縱深防禦：business 於 PUT 時已驗過，但 DB 值可能被 psql 直改或跨環境還原繞過 API。
                // 刻意**不** fallback 到某個預設 Drive 目錄——把檔案倒進使用者雲端硬碟的非預期位置，
                // 比不上傳更糟（本機的 fallback 是為了「不要不寫」，Drive 沒有這個理由）。
                skip = "跳過：Drive 目標資料夾不合法（" + cfg.subpath() + "）";
            }
            if (skip != null) {
                log.warn("Drive 同步{}（{}）", skip, trigger);
                recordGdriveStatusQuietly(skip);
                return new GdriveOutcome(null, null, skip);
            }

            String jsonDest = gdriveUploader.upload(localFile, cfg.subpath(), "public_info_" + today + ".json");
            long jsonSize = Files.size(localFile);
            log.info("Drive 同步成功（{}）：{}（{} bytes）", trigger, jsonDest, jsonSize);
            String jsonStatus = "成功：" + jsonDest + "（" + jsonSize + " bytes）";

            // xlsx 那一份（Requirement 55 / Task 272）：沒產出就記「跳過」，但 JSON 已上傳的事實不受影響。
            String xlsxStatus;
            String xlsxDest = null;
            if (xlsxFile == null) {
                xlsxStatus = "跳過：本輪未產生 Excel";
            } else {
                try {
                    String dest = gdriveUploader.upload(xlsxFile, cfg.subpath(), "public_info_" + today + ".xlsx");
                    long size = Files.size(xlsxFile);
                    log.info("Drive 同步成功（{}）：{}（{} bytes）", trigger, dest, size);
                    xlsxStatus = "成功：" + dest + "（" + size + " bytes）";
                    xlsxDest = dest;
                } catch (Exception e) {
                    log.error("Drive 同步 Excel 失敗（{}）：{}", trigger, e.getMessage(), e);
                    xlsxStatus = "失敗：" + e.getMessage();
                }
            }
            // 字串契約沿用 backend 共用元件的 "xlsx …／json …"，且**必須能分辨是哪一份**；
            // 兩半各自先截斷再合併——合併後才截尾會把 ／json 那一整段切掉。
            // 注意：正常分支一律以 "xlsx " 起頭，故「xlsx 失敗、json 成功」時整串**不以「失敗」開頭**——
            // 呼叫端判斷部分失敗必須用「包含」而非「開頭」（Requirement 63）。
            String merged = halfOf(xlsxStatus, "xlsx ") + "／" + halfOf(jsonStatus, "json ");
            recordGdriveStatusQuietly(merged);
            return new GdriveOutcome(jsonDest, xlsxDest, merged);
        } catch (Exception e) {
            log.error("Drive 同步失敗（{}）：{}", trigger, e.getMessage(), e);
            String status = "失敗：" + e.getMessage();
            recordGdriveStatusQuietly(status);
            return new GdriveOutcome(null, null, status);
        }
    }

    /** 合併前每半的上限：扣掉 {@code "xlsx "} 與 {@code "／json "} 共 12 字元的固定開銷後對半分。 */
    private static String halfOf(String status, String prefix) {
        int max = (512 - 12) / 2;
        String s = status == null ? "" : status;
        return prefix + (s.length() <= max ? s : s.substring(0, max - 1) + "…");
    }

    /**
     * 回寫狀態欄，<b>其自身的失敗也必須被吞掉</b>（DB 短暫不可用時 UPDATE 會擲例外）。
     * 「回報上傳結果」這件事本身不能成為新的失敗來源。
     */
    private void recordGdriveStatusQuietly(String status) {
        try {
            exportPathQuery.recordGdriveResult(CRAWLER_KEY, status);
        } catch (Exception e) {
            log.warn("回寫 Drive 上傳狀態失敗（不影響本機檔與入庫）：{}", e.getMessage());
        }
    }

    /**
     * Drive 子路徑合法性（與 business 端 {@code CrawlerExportPathService} 同一組規則）。
     *
     * <p>{@code ..} 逐段比對而非 {@code contains("..")}，否則會誤擋合法目錄名如 {@code a..b}；
     * 擋 {@code :} 是防子路徑被 rclone 解讀成切換 remote。
     */
    private static boolean isInvalidGdriveSubpath(String subpath) {
        if (subpath == null || subpath.isBlank()) return true;
        if (subpath.startsWith("/") || subpath.contains(":")) return true;
        for (String seg : subpath.split("/")) {
            if ("..".equals(seg)) return true;
        }
        return false;
    }

    /**
     * 本輪的公開資訊 JSON 輸出目錄（Requirement 38 / Task 212）：容器基底 {@code EXPORT_OUTPUT_DIR} resolve
     * {@code crawler_export_setting} 設定的相對子路徑。**每輪即時讀取、不快取**，故頁面改設定後下一輪即生效。
     *
     * <p>寫檔前**再驗一次**跳脫（business 於 PUT 時已驗過一次）：ext 才是實際持有檔案系統寫入權的一方，
     * 不能只信上游驗過——DB 值被繞過 API 直改（psql／備份還原）時仍須擋下。DB 例外／空值／跳脫一律退回
     * {@link #DEFAULT_EXPORT_SUBPATH} 並 warn，避免爬蟲靜默把檔案寫到非預期位置或整個不寫。
     */
    private Path resolveExportDir() {
        String subpath;
        try {
            subpath = exportPathQuery.outputSubpath(CRAWLER_KEY);
        } catch (Exception e) {
            log.warn("讀爬蟲輸出路徑設定失敗，本輪以預設 {} 輸出：{}", DEFAULT_EXPORT_SUBPATH, e.getMessage());
            subpath = null;
        }
        if (subpath == null || subpath.isBlank()) subpath = DEFAULT_EXPORT_SUBPATH;

        Path base = Path.of(exportBaseDir).toAbsolutePath().normalize();
        Path target = base.resolve(subpath).normalize();
        if (!target.startsWith(base)) {
            log.warn("爬蟲輸出子路徑「{}」跳脫基底 {}，本輪改以預設 {} 輸出", subpath, base, DEFAULT_EXPORT_SUBPATH);
            target = base.resolve(DEFAULT_EXPORT_SUBPATH).normalize();
        }
        return target;
    }

    /**
     * 上一交易日 cutoff：優先取 news_headline 中 twse 資料的最新日期（TWSE 權威——遇假日 BFI82U 回最近交易日，
     * 故此日期即上一交易日，且保證 twse 總體資料不被自己的 cutoff 濾掉）；無 twse 資料時才以日曆往回找最近交易日。
     */
    private LocalDate resolveTradingCutoff(LocalDate today) {
        LocalDate cutoff = source.lastTwseTradingDate();
        if (cutoff != null) return cutoff;
        LocalDate d = today;
        for (int i = 0; i < 10 && !calendar.isTwTradingDay(d); i++) d = d.minusDays(1);
        log.warn("公開資訊輸出：news_headline 無 twse 資料，以日曆最近交易日 {} 為 cutoff", d);
        return d;
    }

    /** 去重鍵：sha256(source|url|category)。TWSE URL 帶交易日 → 每日唯一；新聞 URL 每篇唯一。 */
    private static String dedupeKey(NewsRow r) {
        String seed = r.source() + "|" + r.url() + "|" + r.category();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 一定存在；理論上不會到這，退回長度受限的字面鍵
            return Integer.toHexString(seed.hashCode());
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
