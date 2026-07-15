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
 * SRPP 退休規劃專案輸入目錄（Task 177，DB 為單一來源；容器內 {@code news-scraper.export-dir}，docker volume
 * 對映 host），供其量化分析取用；寫檔失敗 graceful，不影響落庫。
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

    private final NewsFetchClient newsClient;
    private final TwseInfoFetchClient twseClient;
    private final MarketSnapshotFetchClient snapshotClient;
    private final KrIntradayFetchClient krIntradayClient;
    private final StockSourceQuery source;
    private final PublicInfoStockFilter stockFilter;
    private final MarketCalendar calendar;
    private final CrawlerScheduleQuery scheduleQuery;
    private final ObjectMapper objectMapper;

    /** 防止上一輪抓取尚未結束又被下一分鐘 ticker 重複觸發（抓取可能耗數十秒）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${news-scraper.enabled:true}")
    private boolean enabled;

    @Value("${news-scraper.retention-days:30}")
    private int retentionDays;

    /** 公開資訊 JSON 輸出目錄（容器內基底，docker volume 對映到 host SRPP/data/input，Task 177）。 */
    @Value("${news-scraper.export-dir:/srpp-input}")
    private String exportDir;

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
     * 但發布日更舊的過期新聞則排除。檔名 {@code public_info_<yyyy-MM-dd>.json}（同日多輪覆寫＝當日最新、跨日
     * 新檔）；內容含 metadata（generatedAt／trigger／tradingDayCutoff／count）與逐則明細。寫檔失敗一律 graceful。
     */
    private void exportPublicInfoJson(String trigger) {
        if (!exportEnabled) return;
        try {
            LocalDate today = LocalDate.now(TW_ZONE);
            LocalDate cutoff = resolveTradingCutoff(today);
            List<NewsRow> items = source.loadTodayPublicInfoForExport(today, cutoff);

            Path dir = Path.of(exportDir);
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
            log.info("公開資訊輸出 JSON（{}）：{} 筆（當日 fetched、published≥{}）→ {}",
                    trigger, items.size(), cutoff, file);
        } catch (Exception e) {
            log.warn("公開資訊輸出 JSON 失敗（{}）：{}", trigger, e.getMessage());
        }
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
