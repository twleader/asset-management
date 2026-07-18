package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 讀取公開資訊爬蟲輸出檔案路徑設定（Requirement 38 / Task 209）：由「爬蟲資訊查詢」頁寫入共用 postgres 的
 * {@code crawler_export_setting}（backend 擁有 schema）。ext 純讀，直接走 JdbcTemplate（不建 entity），
 * 比照 {@link CrawlerScheduleQuery}。表缺／DB 例外時由呼叫端（{@link NewsPoller}）fallback 至預設子路徑。
 */
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
}
