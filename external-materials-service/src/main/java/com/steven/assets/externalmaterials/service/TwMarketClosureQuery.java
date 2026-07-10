package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 台股颱風假 / 臨時休市表 {@code tw_market_closure} 的 JdbcTemplate 存取（Task 160）。
 * 直接走 JdbcTemplate（不用 JPA entity），比照 {@link StockSourceQuery} 慣例，避免與 backend 重複維護 entity。
 * schema 由 backend Liquibase（v1.46.0）建立；本服務僅讀寫。
 */
@Component
@RequiredArgsConstructor
public class TwMarketClosureQuery {

    private final JdbcTemplate jdbc;

    /** 讀全部台股臨時休市日 → {@code {yyyy-MM-dd: reason}}（供 getTwHolidays union）。 */
    public Map<String, String> findAll() {
        Map<String, String> out = new LinkedHashMap<>();
        // 用 void block lambda 綁定 RowCallbackHandler（逐列、cursor 已定位）；expression lambda 會誤綁
        // ResultSetExtractor（拿到未定位的整個 ResultSet）→ 少呼叫 rs.next() 而拋 "not positioned"。
        jdbc.query("SELECT closure_date, reason FROM tw_market_closure ORDER BY closure_date",
                (java.sql.ResultSet rs) -> {
                    out.put(rs.getDate("closure_date").toLocalDate().toString(), rs.getString("reason"));
                });
        return out;
    }

    /** upsert 一筆休市（同日以 closure_date PK 覆蓋 reason / source / raw，detected_at 更新）。 */
    public void upsert(LocalDate date, String reason, String source, String rawStatus) {
        jdbc.update(
                "INSERT INTO tw_market_closure(closure_date, reason, source, raw_status, detected_at) "
                + "VALUES (?, ?, ?, ?, NOW()) "
                + "ON CONFLICT (closure_date) DO UPDATE SET "
                + "reason = EXCLUDED.reason, source = EXCLUDED.source, "
                + "raw_status = EXCLUDED.raw_status, detected_at = NOW()",
                java.sql.Date.valueOf(date), reason, source, rawStatus);
    }
}
