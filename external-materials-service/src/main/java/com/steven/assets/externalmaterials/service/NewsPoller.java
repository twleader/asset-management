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

    /** 防止上一輪抓取尚未結束又被下一分鐘 ticker 重複觸發（抓取可能耗數十秒）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

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
            run(trigger);
        } finally {
            running.set(false);
        }
    }

    private void run(String trigger) {
        List<NewsRow> rows = new ArrayList<>();
        rows.addAll(newsClient.fetchAll());
        rows.addAll(twseClient.fetchAll());
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

        exportPublicInfoJson(trigger);
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
    private void exportPublicInfoJson(String trigger) {
        if (!exportEnabled) return;
        LocalDate today = LocalDate.now(TW_ZONE);
        Path writtenFile = null;   // 本機檔寫成功才會被設值；null＝本機這一步失敗，Drive 不該上傳舊檔
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
            log.info("公開資訊輸出 JSON（{}）：{} 筆（當日 fetched、published≥{}）→ {}",
                    trigger, items.size(), cutoff, file);
        } catch (Exception e) {
            log.warn("公開資訊輸出 JSON 失敗（{}）：{}", trigger, e.getMessage());
        }

        // Drive 同步刻意放在上面 try-catch **之外**：本機寫檔失敗時也要能記錄「跳過」狀態，
        // 否則設定頁會停留在上一次的「成功」，顯示過期的好消息（Requirement 50）。
        syncToGdrive(writtenFile, today, trigger);
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
     * @param localFile 已寫成功的本機檔；{@code null} 表示本機這一步就失敗了
     */
    void syncToGdrive(Path localFile, LocalDate today, String trigger) {
        CrawlerExportPathQuery.GdriveConfig cfg;
        try {
            cfg = exportPathQuery.gdriveConfig(CRAWLER_KEY);
        } catch (Exception e) {
            // 連設定都讀不到就無從得知使用者是否啟用；此時不寫狀態欄（避免在「其實沒啟用」時留下誤導訊息）
            log.warn("讀取 Drive 同步設定失敗（{}），本輪跳過上傳：{}", trigger, e.getMessage());
            return;
        }
        if (!cfg.enabled()) return;   // 未啟用：完全不呼叫 rclone，也不動狀態欄

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
                return;
            }

            String dest = gdriveUploader.upload(localFile, cfg.subpath(), "public_info_" + today + ".json");
            long size = Files.size(localFile);
            log.info("Drive 同步成功（{}）：{}（{} bytes）", trigger, dest, size);
            recordGdriveStatusQuietly("成功：" + dest + "（" + size + " bytes）");
        } catch (Exception e) {
            log.error("Drive 同步失敗（{}）：{}", trigger, e.getMessage(), e);
            recordGdriveStatusQuietly("失敗：" + e.getMessage());
        }
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
