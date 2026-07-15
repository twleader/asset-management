package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 讀取公開資訊爬蟲執行時間設定（Requirement 38 / Task 192）：由「爬蟲資訊查詢」頁寫入共用 postgres 的
 * {@code crawler_schedule}（backend 擁有 schema）。ext 純讀，直接走 JdbcTemplate（不建 entity），
 * 比照 {@link StockSourceQuery}。表缺／DB 例外時由呼叫端（{@link NewsPoller}）fallback 至預設時間。
 */
@Component
@RequiredArgsConstructor
public class CrawlerScheduleQuery {

    private final JdbcTemplate jdbc;

    /** 某爬蟲「已啟用」的執行時間點清單，每筆為 {@code [hour, minute]}。 */
    public List<int[]> enabledTimes(String crawlerKey) {
        List<int[]> times = new ArrayList<>();
        jdbc.query(
                "SELECT run_hour, run_minute FROM crawler_schedule WHERE crawler_key = ? AND enabled = TRUE",
                ps -> ps.setString(1, crawlerKey),
                (java.sql.ResultSet rs) -> {
                    times.add(new int[]{rs.getInt("run_hour"), rs.getInt("run_minute")});
                });
        return times;
    }
}
