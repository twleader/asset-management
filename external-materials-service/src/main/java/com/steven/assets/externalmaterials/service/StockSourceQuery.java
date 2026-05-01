package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 從共用 Postgres 讀取「需要抓哪些股票」的清單，以及寫入收盤價歷史。
 * 直接走 JdbcTemplate（不用 JPA entity），避免和 backend 重複維護 entity。
 */
@Component
@RequiredArgsConstructor
public class StockSourceQuery {

    private final JdbcTemplate jdbc;

    /**
     * 收集「需要抓報價」的股票代號：
     * - 主檔 stock 中的全部代號（涵蓋曾持有 / 觀察 / 警示）
     * - 加上最新快照中持有的代號
     * - 加上觀察清單
     * 三者聯集後依 market 拆兩個 set。
     */
    public void collectAllStockCodes(Set<String> twCodes, Set<String> usCodes) {
        jdbc.query("SELECT code, market FROM stock", rs -> {
            String code = rs.getString("code");
            String market = rs.getString("market");
            if ("美股".equals(market)) usCodes.add(code);
            else twCodes.add(code);
        });
        // 最新快照的持股
        Long latestSnapshotId = jdbc.query(
                "SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (latestSnapshotId != null) {
            jdbc.query("SELECT stock_code, market FROM stock_holding WHERE snapshot_id = ?",
                    ps -> ps.setLong(1, latestSnapshotId),
                    rs -> {
                        String code = rs.getString("stock_code");
                        String market = rs.getString("market");
                        if ("美股".equals(market)) usCodes.add(code);
                        else twCodes.add(code);
                    });
        }
        jdbc.query("SELECT stock_code, market FROM watch_stock", rs -> {
            String code = rs.getString("stock_code");
            String market = rs.getString("market");
            if ("美股".equals(market)) usCodes.add(code);
            else twCodes.add(code);
        });
    }

    /** 取最新快照所有持股代號（盤中 / 盤後皆用同一份）。 */
    public void collectHeldStockCodes(Set<String> twCodes, Set<String> usCodes) {
        Long latestSnapshotId = jdbc.query(
                "SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (latestSnapshotId != null) {
            jdbc.query("SELECT stock_code, market FROM stock_holding WHERE snapshot_id = ?",
                    ps -> ps.setLong(1, latestSnapshotId),
                    rs -> {
                        String code = rs.getString("stock_code");
                        String market = rs.getString("market");
                        if ("美股".equals(market)) usCodes.add(code);
                        else twCodes.add(code);
                    });
        }
        jdbc.query("SELECT stock_code, market FROM watch_stock", rs -> {
            String code = rs.getString("stock_code");
            String market = rs.getString("market");
            if ("美股".equals(market)) usCodes.add(code);
            else twCodes.add(code);
        });
    }

    /** 取該股票歷史表中最近一筆 trading_date（用於 resolveTradingDate fallback）。 */
    public Optional<LocalDate> findMaxTradingDate(String stockCode, String market) {
        return Optional.ofNullable(jdbc.query(
                "SELECT MAX(trading_date) FROM stock_price_history WHERE stock_code = ? AND market = ?",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market); },
                rs -> {
                    if (rs.next()) {
                        LocalDate d = rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate();
                        return d;
                    }
                    return null;
                }));
    }

    /**
     * 寫入或更新 stock_price_history（同一 trading_date 視為覆寫）。
     */
    public void upsertHistory(String stockCode, String market, LocalDate tradingDate,
                              BigDecimal open, BigDecimal high, BigDecimal low,
                              BigDecimal close, Long volume) {
        Long existing = jdbc.query(
                "SELECT id FROM stock_price_history WHERE stock_code=? AND market=? AND trading_date=?",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market); ps.setObject(3, tradingDate); },
                rs -> rs.next() ? rs.getLong(1) : null);
        // open / high / low / close 一律保留 null（無資料），不再用 0 偽裝。
        // close 仍套 nz：上游 dumpRedisToDb 已先用 price 過濾掉 null，留 nz 只是雙保險。
        if (existing != null) {
            jdbc.update(
                    "UPDATE stock_price_history SET open_price=?, high_price=?, low_price=?, close_price=?, volume=? WHERE id=?",
                    open, high, low, nz(close), volume == null ? 0L : volume, existing);
        } else {
            jdbc.update(
                    "INSERT INTO stock_price_history (stock_code, market, trading_date, open_price, high_price, low_price, close_price, volume) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    stockCode, market, tradingDate,
                    open, high, low, nz(close), volume == null ? 0L : volume);
        }
    }

    /** 更新 stock 主檔的 name（若有差異）。upsert：若不存在則插入。 */
    public void upsertStockName(String code, String market, String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase(code)) return;
        int updated = jdbc.update("UPDATE stock SET name=? WHERE code=? AND market=? AND (name IS NULL OR name='' OR name=?)",
                name, code, market, code);
        if (updated == 0) {
            // 嘗試插入（若已存在會被 PK 拒絕，忽略）
            try {
                jdbc.update("INSERT INTO stock (code, market, name) VALUES (?, ?, ?) ON CONFLICT (code, market) DO NOTHING",
                        code, market, name);
            } catch (Exception ignored) {}
        }
    }

    /** 取得除息日前一交易日收盤 (basis) + 填息天數（除息日後幾天股價回到 basis）。 */
    public DividendBasis calcDividendBasis(String stockCode, String market, java.time.LocalDate exDate) {
        java.time.LocalDate from = exDate.minusDays(20);
        java.time.LocalDate to   = exDate.plusDays(400);
        java.util.List<java.time.LocalDate> dates = new java.util.ArrayList<>();
        java.util.List<BigDecimal> closes = new java.util.ArrayList<>();
        jdbc.query(
                "SELECT trading_date, close_price FROM stock_price_history " +
                        "WHERE stock_code=? AND market=? AND trading_date BETWEEN ? AND ? ORDER BY trading_date ASC",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market);
                         ps.setObject(3, from); ps.setObject(4, to); },
                rs -> {
                    dates.add(rs.getDate(1).toLocalDate());
                    closes.add(rs.getBigDecimal(2));
                });
        if (dates.size() < 2) return DividendBasis.EMPTY;
        int exIdx = -1;
        for (int i = 0; i < dates.size(); i++) {
            if (!dates.get(i).isBefore(exDate)) { exIdx = i; break; }
        }
        if (exIdx <= 0) return DividendBasis.EMPTY;
        BigDecimal basis = closes.get(exIdx - 1);
        if (basis == null) return DividendBasis.EMPTY;
        Integer fillDays = null;
        for (int i = exIdx; i < closes.size(); i++) {
            BigDecimal c = closes.get(i);
            if (c != null && c.compareTo(basis) >= 0) {
                fillDays = i - exIdx;
                break;
            }
        }
        return new DividendBasis(basis, fillDays);
    }

    public record DividendBasis(BigDecimal previousClose, Integer fillDays) {
        public static final DividendBasis EMPTY = new DividendBasis(null, null);
    }

    /** Upsert 股利歷史。同一檔某年某 ex-date 視為同一筆覆寫。 */
    public void upsertDividend(String code, String market, Integer year,
                               BigDecimal cashDividend, BigDecimal stockDividend,
                               java.time.LocalDate exDividendDate,
                               java.time.LocalDate cashPaymentDate,
                               java.time.LocalDate stockPaymentDate,
                               BigDecimal yieldPct, Integer fillDays,
                               BigDecimal previousClose, String source) {
        Long existing = exDividendDate != null
                ? jdbc.query(
                        "SELECT id FROM stock_dividend_history " +
                                "WHERE stock_code=? AND market=? AND year=? AND ex_dividend_date=?",
                        ps -> { ps.setString(1, code); ps.setString(2, market);
                                 ps.setInt(3, year); ps.setObject(4, exDividendDate); },
                        rs -> rs.next() ? rs.getLong(1) : null)
                : jdbc.query(
                        "SELECT id FROM stock_dividend_history " +
                                "WHERE stock_code=? AND market=? AND year=? AND ex_dividend_date IS NULL",
                        ps -> { ps.setString(1, code); ps.setString(2, market); ps.setInt(3, year); },
                        rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            jdbc.update(
                    "UPDATE stock_dividend_history SET cash_dividend=?, stock_dividend=?, " +
                            "ex_dividend_date=?, yield_pct=?, cash_payment_date=?, stock_payment_date=?, " +
                            "fill_days=?, previous_close=?, source=?, updated_at=NOW() WHERE id=?",
                    cashDividend, stockDividend,
                    exDividendDate, yieldPct,
                    cashPaymentDate, stockPaymentDate,
                    fillDays, previousClose, source, existing);
        } else {
            jdbc.update(
                    "INSERT INTO stock_dividend_history (stock_code, market, year, cash_dividend, " +
                            "stock_dividend, ex_dividend_date, yield_pct, cash_payment_date, " +
                            "stock_payment_date, fill_days, previous_close, source, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                    code, market, year, cashDividend, stockDividend,
                    exDividendDate, yieldPct,
                    cashPaymentDate, stockPaymentDate,
                    fillDays, previousClose, source);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public Set<String> emptyCodeSet() { return new LinkedHashSet<>(); }
}
