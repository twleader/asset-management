package com.steven.assets.externalmaterials.service;

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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地財經新聞抓取排程（Task 149.21）：每日 06:00 / 12:00 / 18:00（Asia/Taipei）抓權威新聞
 * （玩股網 / MoneyDJ / 自由時報 / 經濟日報）＋證交所公開資訊（三大法人、大盤成交），去重後 upsert 至 news_headline，
 * 供 business-services 的今日股市分析餵入 prompt。**06:00 那次早於 07:30 分析**，確保當日有料。
 *
 * <p>開機 warmup（{@link ApplicationReadyEvent}）先跑一次，部署後立即有資料。每次末尾清理保留期外舊聞。
 * 逐來源／逐則 graceful：任一失敗只 log warn、不影響其他，比照既有 producer 慣例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsPoller {

    private final NewsFetchClient newsClient;
    private final TwseInfoFetchClient twseClient;
    private final StockSourceQuery source;

    @Value("${news-scraper.enabled:true}")
    private boolean enabled;

    @Value("${news-scraper.retention-days:30}")
    private int retentionDays;

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        if (!enabled) {
            log.info("本地新聞爬蟲已停用（news-scraper.enabled=false），略過 warmup");
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                run("warmup");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("本地新聞 warmup 失敗：{}", e.getMessage());
            }
        }, "news-warmup").start();
    }

    /** 每日 06:00 / 12:00 / 18:00（Asia/Taipei）。06:00 早於 07:30 分析。 */
    @Scheduled(cron = "0 0 6,12,18 * * *", zone = "Asia/Taipei")
    public void scheduled() {
        if (!enabled) return;
        run("scheduled");
    }

    private void run(String trigger) {
        List<NewsRow> rows = new ArrayList<>();
        rows.addAll(newsClient.fetchAll());
        rows.addAll(twseClient.fetchAll());

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
