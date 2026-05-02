package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * fund_master 讀取 + fund_nav upsert（Requirement 19）。
 * 直接 JdbcTemplate，避免和 backend 重複維護 entity（同 StockSourceQuery 慣例）。
 */
@Component
@RequiredArgsConstructor
public class FundNavSourceQuery {

    private final JdbcTemplate jdbc;

    public record FundMasterRow(String fundCode, String currency, String site,
                                String fundclearOrgCode, String fundclearFundCode,
                                String fundclearClassCode) {}

    public List<FundMasterRow> findActiveFunds() {
        List<FundMasterRow> out = new ArrayList<>();
        jdbc.query("SELECT fund_code, currency, site, fundclear_org_code, fundclear_fund_code, "
                + "fundclear_class_code FROM fund_master WHERE active = TRUE", rs -> {
            out.add(new FundMasterRow(
                    rs.getString("fund_code"),
                    rs.getString("currency"),
                    rs.getString("site"),
                    rs.getString("fundclear_org_code"),
                    rs.getString("fundclear_fund_code"),
                    rs.getString("fundclear_class_code")));
        });
        return out;
    }

    /**
     * upsert fund_nav。同 (fund_code, nav_date) 視為覆寫（fetched_at 更新）。
     */
    public void upsertNav(String fundCode, LocalDate navDate, BigDecimal nav, String source) {
        Long existing = jdbc.query(
                "SELECT id FROM fund_nav WHERE fund_code=? AND nav_date=?",
                ps -> { ps.setString(1, fundCode); ps.setObject(2, navDate); },
                rs -> rs.next() ? rs.getLong(1) : null);
        Instant now = Instant.now();
        if (existing != null) {
            jdbc.update("UPDATE fund_nav SET nav=?, source=?, fetched_at=? WHERE id=?",
                    nav, source, java.sql.Timestamp.from(now), existing);
        } else {
            jdbc.update("INSERT INTO fund_nav (fund_code, nav_date, nav, source, fetched_at) "
                    + "VALUES (?, ?, ?, ?, ?)",
                    fundCode, navDate, nav, source, java.sql.Timestamp.from(now));
        }
    }

    /**
     * upsert fund_dividend_history (Requirement 20)。同 (fund_code, base_date) 視為覆寫。
     */
    public void upsertDividend(String fundCode, LocalDate baseDate, BigDecimal amount,
                               String currency, String frequency) {
        Long existing = jdbc.query(
                "SELECT id FROM fund_dividend_history WHERE fund_code=? AND base_date=?",
                ps -> { ps.setString(1, fundCode); ps.setObject(2, baseDate); },
                rs -> rs.next() ? rs.getLong(1) : null);
        Instant now = Instant.now();
        if (existing != null) {
            jdbc.update("UPDATE fund_dividend_history SET amount=?, currency=?, frequency=?, fetched_at=? WHERE id=?",
                    amount, currency, frequency, java.sql.Timestamp.from(now), existing);
        } else {
            jdbc.update("INSERT INTO fund_dividend_history (fund_code, base_date, amount, currency, frequency, fetched_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)",
                    fundCode, baseDate, amount, currency, frequency, java.sql.Timestamp.from(now));
        }
    }
}
