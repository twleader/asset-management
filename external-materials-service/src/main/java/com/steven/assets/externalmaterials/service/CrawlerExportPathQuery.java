package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 讀取公開資訊爬蟲輸出檔案路徑設定（Requirement 38 / Task 212）：由「爬蟲資訊查詢」頁寫入共用 postgres 的
 * {@code crawler_export_setting}（backend 擁有 schema）。ext 純讀，直接走 JdbcTemplate（不建 entity），
 * 比照 {@link CrawlerScheduleQuery}。表缺／DB 例外時由呼叫端（{@link NewsPoller}）fallback 至預設子路徑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlerExportPathQuery {

    private final JdbcTemplate jdbc;

    /** 某爬蟲設定的輸出相對子路徑；查無設定回 {@code null}（呼叫端套預設）。 */
    public String outputSubpath(String crawlerKey) {
        List<String> rows = jdbc.query(
                "SELECT output_subpath FROM crawler_export_setting WHERE crawler_key = ?",
                ps -> ps.setString(1, crawlerKey),
                (rs, rowNum) -> rs.getString("output_subpath"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Google Drive 同步設定（Requirement 50 / Task 241）。 */
    public record GdriveConfig(boolean enabled, String subpath) {
        /** 查無設定／讀取失敗時的安全預設：不上傳。 */
        public static GdriveConfig disabled() {
            return new GdriveConfig(false, null);
        }
    }

    /**
     * 某爬蟲的 Drive 同步設定；查無列時回 {@link GdriveConfig#disabled()}。
     *
     * <p><b>一次查詢取兩欄</b>，不拆成兩支各查一次。用<b>雙參數</b> {@code RowMapper}
     * {@code (rs, rowNum) -> ...}：Spring 會逐列呼叫並已把 {@code rs} 定位好。
     * <b>不要寫成單參數</b> {@code (rs) -> ...}——那會被解析成 {@code ResultSetExtractor}，
     * Spring 只呼叫一次且 {@code rs} <b>未</b> {@code next()} 定位，讀欄位會 runtime 才炸。
     */
    public GdriveConfig gdriveConfig(String crawlerKey) {
        List<GdriveConfig> rows = jdbc.query(
                "SELECT gdrive_enabled, gdrive_subpath FROM crawler_export_setting WHERE crawler_key = ?",
                ps -> ps.setString(1, crawlerKey),
                (rs, rowNum) -> new GdriveConfig(rs.getBoolean("gdrive_enabled"), rs.getString("gdrive_subpath")));
        return rows.isEmpty() ? GdriveConfig.disabled() : rows.get(0);
    }

    /**
     * 回寫 Drive 上傳結果（Requirement 50 / Task 241）。
     *
     * <p><b>這是 ext 對 {@code crawler_export_setting} 的唯一寫入</b>，且只碰 {@code gdrive_last_run_at}
     * 與 {@code gdrive_last_status} 兩欄——ext 對此表原本純讀（schema 由 backend 擁有），此處為刻意的例外：
     * 上傳結果只有 ext 知道，沒有別的地方能寫。
     *
     * <p><b>命中 0 列時只 warn、不 upsert</b>：該列可能不存在（business 查無設定時回的是未落庫的預設物件），
     * 但列的所有權在 backend——若 ext 也能建列，{@code crawler_key} UNIQUE 的競態與所有權就模糊了。
     *
     * <p>狀態訊息截斷至欄位長度內（rclone stderr 可能很長）。
     */
    public void recordGdriveResult(String crawlerKey, String status) {
        String msg = status == null ? null
                : (status.length() > MAX_STATUS_LEN ? status.substring(0, MAX_STATUS_LEN) : status);
        int updated = jdbc.update(
                "UPDATE crawler_export_setting SET gdrive_last_run_at = ?, gdrive_last_status = ? WHERE crawler_key = ?",
                Timestamp.from(Instant.now()), msg, crawlerKey);
        if (updated == 0) {
            log.warn("回寫 Drive 上傳狀態時查無 crawler_export_setting 列（crawler_key={}），"
                    + "本次狀態未記錄；該列由 backend 擁有，ext 不代為建立。狀態內容：{}", crawlerKey, msg);
        }
    }

    /** {@code gdrive_last_status} 欄位長度上限。 */
    private static final int MAX_STATUS_LEN = 512;
}
