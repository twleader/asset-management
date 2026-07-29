package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.NewsRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** stock 主檔全部代號（不分市場）集合，供公開資訊個股過濾（Task 178）。 */
    public Set<String> allStockCodes() {
        Set<String> codes = new LinkedHashSet<>();
        jdbc.query("SELECT code FROM stock", (java.sql.ResultSet rs) -> {
            codes.add(rs.getString("code"));
        });
        return codes;
    }

    /**
     * 收集「需要抓報價」的股票代號：
     * - 主檔 stock 中的全部代號（涵蓋曾持有 / 觀察 / 警示）
     * - 加上最新快照中持有的代號
     * - 加上觀察清單
     * 三者聯集後依 market 拆兩個 set。
     */
    public void collectAllStockCodes(Set<String> twCodes, Set<String> usCodes, Set<String> ukCodes) {
        jdbc.query("SELECT code, market FROM stock", (java.sql.ResultSet rs) -> {
            classify(rs.getString("code"), rs.getString("market"), twCodes, usCodes, ukCodes);
        });
        // 最新快照的持股
        Long latestSnapshotId = jdbc.query(
                "SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (latestSnapshotId != null) {
            jdbc.query("SELECT stock_code, market FROM stock_holding WHERE snapshot_id = ?",
                    ps -> ps.setLong(1, latestSnapshotId),
                    (java.sql.ResultSet rs) -> {
                        classify(rs.getString("stock_code"), rs.getString("market"),
                                twCodes, usCodes, ukCodes);
                    });
        }
        // watch_stock 表已廢止（v1.22）；觀察清單由 stock_alert 衍生
        // 排除 0000（台股大盤）— 走 twse_index_daily_history，不打 TWSE mis API
        jdbc.query("SELECT DISTINCT stock_code, market FROM stock_alert " +
                "WHERE NOT (stock_code = '0000' AND market = '台股')",
                (java.sql.ResultSet rs) -> {
                    classify(rs.getString("stock_code"), rs.getString("market"),
                            twCodes, usCodes, ukCodes);
                });
    }

    /**
     * 收集需要抓報價的持股代號：<b>每位 owner 各自</b>最新快照的持股 ∪ {@code stock_alert} 觀察清單。
     * 盤中 / 盤後皆用同一份。
     *
     * <p><b>Task 257 起改為 per-owner。</b>原本取
     * {@code SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1}——全庫只取一筆快照。
     * 本系統為多租戶，且 Requirement 35 每日把各 owner 的最新快照日期都釘成當日，於是多位 owner 的
     * 最新快照必然同日、tie-break 由 Postgres 任意決定。實測 2026-07-29：owner 1 的快照 id 15 有 35 筆
     * 台股持股、owner 2 的 id 18 只有 2 筆，Postgres 挑中 id 18，使 {@code 2885}（元大金，只在 owner 1
     * 持股、不在觀察清單）既不被每 2 分鐘的 {@code PricePoller.scheduledTwIntradayUpdate} 抓價、
     * 也不被 16:00 的 {@code ClosePersister.verifyTwCloseWithFinMind} 校正收盤，前端因而顯示前一交易日
     * 的假收盤（63.50，實際 07-29 收盤 62.2）。</p>
     *
     * <p>Task 249 曾判斷「改本方法會放大背景排程對外部 API 的請求量」而不動它，改為新增
     * {@link #collectTwRadarCodes(Set)} 繞過。<b>該判斷經量測後不成立</b>：改為 per-owner 後實測
     * 台股 18 → 19 檔（只多 {@code 2885} 一檔）、美股 9 → 9、英股 3 → 3。原因是 {@code stock_holding}
     * 同一檔股票在同一快照內可依券商／帳戶分列，去重後與觀察清單高度重疊。兩者現已同口徑。</p>
     *
     * <p>{@code ORDER BY} 三個欄位一個都不能少：{@code owner_user_id} 是 {@code DISTINCT ON} 的必要
     * 前綴、{@code snapshot_date DESC} 取最新、{@code id DESC} 是同 owner 同日多筆時的決定性 tie-break
     * （少了它就退回本 bug 的「任意 tie-break」）。</p>
     */
    public void collectHeldStockCodes(Set<String> twCodes, Set<String> usCodes, Set<String> ukCodes) {
        jdbc.query("SELECT stock_code, market FROM stock_holding "
                        + "WHERE snapshot_id IN (SELECT DISTINCT ON (owner_user_id) id FROM asset_snapshot "
                        + "ORDER BY owner_user_id, snapshot_date DESC, id DESC)",
                (java.sql.ResultSet rs) -> {
                    classify(rs.getString("stock_code"), rs.getString("market"),
                            twCodes, usCodes, ukCodes);
                });
        // watch_stock 表已廢止（v1.22）；觀察清單由 stock_alert 衍生
        // 排除 0000（台股大盤）— 走 twse_index_daily_history，不打 TWSE mis API
        jdbc.query("SELECT DISTINCT stock_code, market FROM stock_alert " +
                "WHERE NOT (stock_code = '0000' AND market = '台股')",
                (java.sql.ResultSet rs) -> {
                    classify(rs.getString("stock_code"), rs.getString("market"),
                            twCodes, usCodes, ukCodes);
                });
    }

    /**
     * 今日交易雷達回補專用：**每位 owner 各自最新快照**的台股持股 ∪ 台股觀察清單，排除大盤 `0000`（Task 249）。
     *
     * <p><b>為什麼不重用 {@link #collectHeldStockCodes}：</b>後者取的是
     * {@code SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1}——
     * 全庫只取<b>一筆</b>，且日期相同時 tie-break 由 Postgres 任意決定。實測 2026-07-29 兩位 owner
     * 同日各有一筆快照（id 15 / owner 1，35 筆台股；id 18 / owner 2，2 筆台股），它挑中 id 18，
     * 於是 owner 1 只在自己持股、不在觀察清單的標的（實測 `2885`）永遠不會被回補——
     * 使用者按下「重新整理」卻有一列價格沒動，與按鈕的承諾不符。</p>
     *
     * <p>改用 {@code DISTINCT ON (owner_user_id) ... ORDER BY owner_user_id, snapshot_date DESC, id DESC}
     * 取每位 owner 的最新快照（同 owner 同日多筆時再以 id 決勝，結果具決定性）。範圍仍是全庫——
     * Redis 行情快取本就是跨租戶共用的市場資料，且回應不回傳任何檔數或代號，不構成租戶洩漏。</p>
     *
     * <p><b>刻意不改 {@link #collectHeldStockCodes} 本身</b>：它同時服務每 2 分鐘的
     * {@code PricePoller.scheduledTwIntradayUpdate} 與 {@code refreshAll()}，放大其範圍會改變背景排程
     * 對外部 API 的請求量，屬另一個決定。</p>
     *
     * <p><b>（Task 257 已推翻上一段）</b>實測改為 per-owner 後台股僅 18 → 19 檔、美股與英股不變，
     * 請求量幾無變化；{@link #collectHeldStockCodes} 現已改為同一口徑。上一段保留為當時的決策記錄。</p>
     */
    public void collectTwRadarCodes(Set<String> twCodes) {
        jdbc.query("SELECT h.stock_code FROM stock_holding h WHERE h.market = '台股' "
                        + "AND h.snapshot_id IN (SELECT DISTINCT ON (owner_user_id) id FROM asset_snapshot "
                        + "ORDER BY owner_user_id, snapshot_date DESC, id DESC)",
                (java.sql.ResultSet rs) -> { twCodes.add(rs.getString("stock_code")); });
        jdbc.query("SELECT DISTINCT stock_code FROM stock_alert WHERE market = '台股'",
                (java.sql.ResultSet rs) -> { twCodes.add(rs.getString("stock_code")); });
        twCodes.remove("0000");   // 大盤走 twse_index_daily_history / Yahoo ^TWII，不打 TWSE mis API
    }

    private static void classify(String code, String market,
                                 Set<String> twCodes, Set<String> usCodes, Set<String> ukCodes) {
        if ("美股".equals(market)) usCodes.add(code);
        else if ("英股".equals(market)) ukCodes.add(code);
        else twCodes.add(code);
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

    /** 取該股票歷史表中最早一筆 trading_date（用於判斷是否需要向前回補）。 */
    public Optional<LocalDate> findMinTradingDate(String stockCode, String market) {
        return Optional.ofNullable(jdbc.query(
                "SELECT MIN(trading_date) FROM stock_price_history WHERE stock_code = ? AND market = ?",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market); },
                rs -> {
                    if (rs.next()) {
                        LocalDate d = rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate();
                        return d;
                    }
                    return null;
                }));
    }

    /** Upsert 台股大盤每日 OHLC（同一 trading_date 視為覆寫；OHLC 任一者可為 null）。 */
    public void upsertTwseIndexDaily(LocalDate tradingDate,
                                      BigDecimal openPoint, BigDecimal highPoint,
                                      BigDecimal lowPoint, BigDecimal closePoint) {
        Long existing = jdbc.query(
                "SELECT 1 FROM twse_index_daily_history WHERE trading_date=?",
                ps -> ps.setObject(1, tradingDate),
                rs -> rs.next() ? 1L : null);
        if (existing != null) {
            jdbc.update("UPDATE twse_index_daily_history " +
                            "SET open_point=?, high_point=?, low_point=?, close_point=? WHERE trading_date=?",
                    openPoint, highPoint, lowPoint, closePoint, tradingDate);
        } else {
            jdbc.update("INSERT INTO twse_index_daily_history " +
                            "(trading_date, open_point, high_point, low_point, close_point) VALUES (?, ?, ?, ?, ?)",
                    tradingDate, openPoint, highPoint, lowPoint, closePoint);
        }
    }

    /** 該股票歷史表最近一筆收盤價（用於 dividend yield 分母 fallback）。 */
    public Optional<BigDecimal> findRecentClose(String stockCode, String market) {
        return Optional.ofNullable(jdbc.query(
                "SELECT close_price FROM stock_price_history WHERE stock_code=? AND market=? " +
                        "ORDER BY trading_date DESC LIMIT 1",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market); },
                rs -> {
                    if (rs.next()) {
                        return rs.getBigDecimal(1);
                    }
                    return null;
                }));
    }

    /** 取指定日期之前最近一個交易日的收盤價（休市 DB→Redis 同步的昨收權威來源）。 */
    public Optional<BigDecimal> findPreviousCloseBefore(
            String stockCode, String market, LocalDate beforeDate) {
        return Optional.ofNullable(jdbc.query(
                "SELECT close_price FROM stock_price_history " +
                        "WHERE stock_code=? AND market=? AND trading_date < ? AND close_price IS NOT NULL " +
                        "ORDER BY trading_date DESC LIMIT 1",
                ps -> {
                    ps.setString(1, stockCode);
                    ps.setString(2, market);
                    ps.setObject(3, beforeDate);
                },
                rs -> rs.next() ? rs.getBigDecimal(1) : null));
    }

    /** 指定交易日的收盤價（Task 215：入庫折溢價時，與淨值配對的必須是<b>同一交易日</b>的收盤價）。 */
    public Optional<BigDecimal> findCloseOn(String stockCode, String market, LocalDate tradingDate) {
        return Optional.ofNullable(jdbc.query(
                "SELECT close_price FROM stock_price_history WHERE stock_code=? AND market=? AND trading_date=?",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market); ps.setObject(3, tradingDate); },
                rs -> rs.next() ? rs.getBigDecimal(1) : null));
    }

    /** 一筆日收盤（交易日＋收盤價），供均線計算（Task 207）。 */
    public record ClosePoint(LocalDate date, BigDecimal close) {}

    /**
     * 取某台股個股最近 n 筆日收盤（{@code stock_price_history}，濾除 null 收盤），<b>由舊到新</b>排序，供均線
     * （季線 MA60／年線 MA240）計算（Task 207）。多列查詢用 void 區塊 lambda（RowCallbackHandler，逐列已定位）。
     */
    public List<ClosePoint> loadRecentStockCloses(String stockCode, String market, int n) {
        List<ClosePoint> rows = new java.util.ArrayList<>();
        jdbc.query(
                "SELECT trading_date, close_price FROM stock_price_history " +
                        "WHERE stock_code=? AND market=? AND close_price IS NOT NULL " +
                        "ORDER BY trading_date DESC LIMIT ?",
                (java.sql.ResultSet rs) -> {
                    rows.add(new ClosePoint(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)));
                }, stockCode, market, n);
        java.util.Collections.reverse(rows);   // DESC 撈回後反轉為由舊到新
        return rows;
    }

    /**
     * 取台股大盤（0000）最近 n 筆日收盤（{@code twse_index_daily_history.close_point}，濾除 null），
     * <b>由舊到新</b>排序，供均線計算（Task 207）。大盤走指數表、不在 stock_price_history。
     */
    public List<ClosePoint> loadRecentTaiexCloses(int n) {
        List<ClosePoint> rows = new java.util.ArrayList<>();
        jdbc.query(
                "SELECT trading_date, close_point FROM twse_index_daily_history " +
                        "WHERE close_point IS NOT NULL ORDER BY trading_date DESC LIMIT ?",
                (java.sql.ResultSet rs) -> {
                    rows.add(new ClosePoint(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)));
                }, n);
        java.util.Collections.reverse(rows);
        return rows;
    }

    /** 判斷該股票該日是否已有歷史紀錄（避免回補重複插入）。 */
    public boolean existsHistory(String stockCode, String market, LocalDate tradingDate) {
        org.springframework.jdbc.core.ResultSetExtractor<Boolean> ex = rs -> rs.next();
        Boolean b = jdbc.query(
                "SELECT 1 FROM stock_price_history WHERE stock_code=? AND market=? AND trading_date=? LIMIT 1",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market); ps.setObject(3, tradingDate); },
                ex);
        return Boolean.TRUE.equals(b);
    }

    /** 取匯率最早日期（用於判斷是否需向前回補 10 年）。 */
    public Optional<LocalDate> findMinRateDate(String currency) {
        return Optional.ofNullable(jdbc.query(
                "SELECT MIN(rate_date) FROM exchange_rate_history WHERE currency = ?",
                ps -> ps.setString(1, currency),
                rs -> {
                    if (rs.next()) {
                        return rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate();
                    }
                    return null;
                }));
    }

    /** 取匯率最近日期（增量回補時用）。 */
    public Optional<LocalDate> findMaxRateDate(String currency) {
        return Optional.ofNullable(jdbc.query(
                "SELECT MAX(rate_date) FROM exchange_rate_history WHERE currency = ?",
                ps -> ps.setString(1, currency),
                rs -> {
                    if (rs.next()) {
                        return rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate();
                    }
                    return null;
                }));
    }

    /** Upsert 匯率歷史（同一 currency+rate_date 視為覆寫）。 */
    public void upsertExchangeRate(String currency, LocalDate rateDate, BigDecimal buyRate, BigDecimal sellRate) {
        Long existing = jdbc.query(
                "SELECT id FROM exchange_rate_history WHERE currency=? AND rate_date=?",
                ps -> { ps.setString(1, currency); ps.setObject(2, rateDate); },
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            jdbc.update(
                    "UPDATE exchange_rate_history SET buy_rate=?, sell_rate=? WHERE id=?",
                    buyRate, sellRate, existing);
        } else {
            jdbc.update(
                    "INSERT INTO exchange_rate_history (currency, rate_date, buy_rate, sell_rate) VALUES (?, ?, ?, ?)",
                    currency, rateDate, buyRate, sellRate);
        }
    }

    /** 取原物料（油／金）最早日期（用於判斷是否需向前回補 10 年）。 */
    public Optional<LocalDate> findMinCommodityDate(String code) {
        return Optional.ofNullable(jdbc.query(
                "SELECT MIN(price_date) FROM commodity_price_history WHERE commodity_code = ?",
                ps -> ps.setString(1, code),
                rs -> {
                    if (rs.next()) {
                        return rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate();
                    }
                    return null;
                }));
    }

    /** 取原物料（油／金）最近日期（增量回補時用）。 */
    public Optional<LocalDate> findMaxCommodityDate(String code) {
        return Optional.ofNullable(jdbc.query(
                "SELECT MAX(price_date) FROM commodity_price_history WHERE commodity_code = ?",
                ps -> ps.setString(1, code),
                rs -> {
                    if (rs.next()) {
                        return rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate();
                    }
                    return null;
                }));
    }

    /** Upsert 原物料收盤價（同一 commodity_code+price_date 視為覆寫）。 */
    public void upsertCommodityPrice(String code, LocalDate priceDate, BigDecimal closePrice) {
        Long existing = jdbc.query(
                "SELECT id FROM commodity_price_history WHERE commodity_code=? AND price_date=?",
                ps -> { ps.setString(1, code); ps.setObject(2, priceDate); },
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            jdbc.update(
                    "UPDATE commodity_price_history SET close_price=? WHERE id=?",
                    closePrice, existing);
        } else {
            jdbc.update(
                    "INSERT INTO commodity_price_history (commodity_code, price_date, close_price) VALUES (?, ?, ?)",
                    code, priceDate, closePrice);
        }
    }

    /**
     * ETF 每日淨值／折溢價入庫（Task 215）：同一 (代號, 市場, 資料日) 覆寫。
     *
     * <p>盤中多次抓取會反覆覆寫同一列，故每日最終值＝當日最後一次抓到的值（收盤後那次），
     * 這正是「當日收盤折溢價」的語意。
     *
     * <p>折溢價原樣保存來源值，<b>不由淨值反推</b>——台股該值是證交所發布的權威數字，而其淨值欄在股票型
     * ETF 已四捨五入至小數 2 位，反推誤差達 0.07 個百分點。市價刻意不入此表（同一事實已在
     * {@code stock_price_history.close_price}，跨表重複違反完整正規化）。
     */
    public void upsertEtfNav(String stockCode, String market, LocalDate navDate,
                             BigDecimal nav, BigDecimal premiumDiscountPct, String source) {
        Long existing = jdbc.query(
                "SELECT id FROM etf_nav_history WHERE stock_code=? AND market=? AND nav_date=?",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market); ps.setObject(3, navDate); },
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            jdbc.update("UPDATE etf_nav_history SET nav=?, premium_discount_pct=?, source=? WHERE id=?",
                    nav, premiumDiscountPct, source, existing);
        } else {
            jdbc.update("INSERT INTO etf_nav_history "
                    + "(stock_code, market, nav_date, nav, premium_discount_pct, source) VALUES (?, ?, ?, ?, ?, ?)",
                    stockCode, market, navDate, nav, premiumDiscountPct, source);
        }
    }

    /** 系統需追蹤的非 TWD 計價幣別：強制含 USD（美股），加上 fund_master.active 上的非 TWD 幣別。 */
    public Set<String> collectTrackedCurrencies() {
        Set<String> set = new LinkedHashSet<>();
        set.add("USD");
        jdbc.query("SELECT DISTINCT currency FROM fund_master WHERE active = TRUE", rs -> {
            String c = rs.getString(1);
            if (c != null && !c.isBlank() && !"TWD".equalsIgnoreCase(c.trim())) {
                set.add(c.trim().toUpperCase());
            }
        });
        return set;
    }

    /**
     * 收集所有需要回補歷史價格的股票代號：stock 主檔 ∪ 歷史所有 snapshot 的持股
     * （與 collectAllStockCodes 不同：後者只看最新 snapshot；這個版本要涵蓋曾經持有的）。
     */
    public void collectAllHeldCodes(Set<String> twCodes, Set<String> usCodes, Set<String> ukCodes) {
        jdbc.query("SELECT code, market FROM stock", (java.sql.ResultSet rs) -> {
            classify(rs.getString("code"), rs.getString("market"), twCodes, usCodes, ukCodes);
        });
        jdbc.query("SELECT DISTINCT stock_code, market FROM stock_holding", (java.sql.ResultSet rs) -> {
            classify(rs.getString("stock_code"), rs.getString("market"), twCodes, usCodes, ukCodes);
        });
    }

    /** 最新快照單一持股（含市值），供 ETF 透視 top10 成份股回補加權（Task 129）。 */
    public record HeldValueRow(String stockCode, String market, BigDecimal currentValue) {}

    /**
     * 讀最新快照 `stock_holding` 的 (stock_code, market, current_value)。
     * 供 {@code HistoricalBackfillService.collectLookthroughTopConstituents} 重算透視 top10 成份股（Task 129）。
     */
    public java.util.List<HeldValueRow> collectLatestSnapshotHoldingsWithValue() {
        java.util.List<HeldValueRow> rows = new java.util.ArrayList<>();
        Long latestSnapshotId = jdbc.query(
                "SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (latestSnapshotId == null) return rows;
        jdbc.query("SELECT stock_code, market, current_value FROM stock_holding WHERE snapshot_id = ?",
                ps -> ps.setLong(1, latestSnapshotId),
                (java.sql.ResultSet rs) -> {
                    rows.add(new HeldValueRow(
                            rs.getString("stock_code"),
                            rs.getString("market"),
                            rs.getBigDecimal("current_value")));
                });
        return rows;
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

    /**
     * 颱風假一體休市：刪除台股某休市日的 stock_price_history（偵測落後於盤中時，13:32 收盤 dump
     * 已把昨收平盤誤寫成當日收盤）。嚴格限「台股」——颱風假僅台股休市，英股 / 美股同日照常交易、不得動。
     * 回刪除筆數。
     */
    public int deleteTwHistoryOn(LocalDate tradingDate) {
        return jdbc.update(
                "DELETE FROM stock_price_history WHERE market = '台股' AND trading_date = ?",
                ps -> ps.setObject(1, tradingDate));
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

    // ===== 本地財經新聞 news_headline（Task 149.21）：ext 直寫、backend JPA 讀 =====

    /**
     * Upsert 一則新聞/公開資訊至 news_headline，以 {@code dedupe_key} 去重
     * （同 key 更新 title/summary/published_at＋刷新 fetched_at）。
     * published_at 以 OffsetDateTime(UTC) 綁定 TIMESTAMPTZ，避免 java.sql.Timestamp 的時區歧義。
     */
    public void upsertNews(String title, String source, String url, String category,
                           String region, String summary,
                           java.time.Instant publishedAt, String dedupeKey) {
        jdbc.update(
                "INSERT INTO news_headline " +
                        "(title, source, url, category, region, summary, published_at, fetched_at, dedupe_key) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?) " +
                        "ON CONFLICT (dedupe_key) DO UPDATE SET " +
                        "  title = EXCLUDED.title, summary = EXCLUDED.summary, " +
                        "  published_at = EXCLUDED.published_at, fetched_at = NOW()",
                title, source, url, category, region, summary,
                publishedAt.atOffset(java.time.ZoneOffset.UTC), dedupeKey);
    }

    /** 刪除 published_at 早於 cutoff 的舊新聞（保留期清理）。回刪除筆數。 */
    public int deleteNewsOlderThan(java.time.Instant cutoff) {
        return jdbc.update("DELETE FROM news_headline WHERE published_at < ?",
                cutoff.atOffset(java.time.ZoneOffset.UTC));
    }

    /**
     * 「上一交易日」＝ news_headline 中 twse 總體資料（category twse-*）的最新資料日（Asia/Taipei）。
     * twse 資料日期為 TWSE 權威（BFI82U 遇假日回最近交易日），即為 SRPP JSON 當日範圍的 published_at 下界；
     * 取自身日期而非日曆，可保證三大法人／大盤成交（其日期＝上一交易日）不被濾掉（Task 177）。
     * 無 twse 資料時回 null（由呼叫端以日曆 fallback）。
     */
    public LocalDate lastTwseTradingDate() {
        java.sql.Date d = jdbc.queryForObject(
                "SELECT MAX((published_at AT TIME ZONE 'Asia/Taipei')::date) " +
                        "FROM news_headline WHERE category LIKE 'twse-%'",
                java.sql.Date.class);
        return d == null ? null : d.toLocalDate();
    }

    /**
     * 「當日公開資訊」＝今天(Asia/Taipei)這批爬蟲抓進來的（fetched_at 為今天）、且資料日期 published_at
     * 不早於 cutoff（上一交易日）的列，供 SRPP JSON 輸出（Task 177，DB 為單一來源）。個股過濾已於 upsert 前
     * 套用，故 DB／此查詢自然只含過濾後資料。tags 為 @JsonIgnore、DB 不存，回 NewsRow 時為空。
     */
    public List<NewsRow> loadTodayPublicInfoForExport(LocalDate todayTw, LocalDate cutoff) {
        return jdbc.query(
                "SELECT title, source, url, category, region, summary, published_at " +
                        "FROM news_headline " +
                        "WHERE (fetched_at AT TIME ZONE 'Asia/Taipei')::date = ? " +
                        // Task 207：ma-cross 的 published_at 刻意取「事件資料日」（供分析視窗自然老化），而大盤指數表
                        // 落後數日屬常態（TwseIndexPoller 08:30 補抓晚於 NewsPoller 08:20 盤前輪），套 cutoff 會讓
                        // 該列被 SRPP JSON 靜默濾掉、且同日不同輪次時有時無。故本 category 只受 fetched_at 當日管。
                        "  AND (category = 'ma-cross' " +
                        "       OR (published_at AT TIME ZONE 'Asia/Taipei')::date >= ?) " +
                        "ORDER BY published_at DESC, category, source",
                (rs, i) -> new NewsRow(
                        rs.getString("title"), rs.getString("source"), rs.getString("url"),
                        rs.getString("category"), rs.getString("region"), rs.getString("summary"),
                        rs.getObject("published_at", java.time.OffsetDateTime.class).toInstant()),
                java.sql.Date.valueOf(todayTw), java.sql.Date.valueOf(cutoff));
    }

    public Set<String> emptyCodeSet() { return new LinkedHashSet<>(); }

    // ===== 公開資訊快照：匯率 + 美股指數（Task 180，供 NewsPoller 組 NewsRow 併入 news_headline/SRPP JSON）=====

    /** 最新一筆 USD 匯率（買/賣即期價與資料日）；無資料回 null。 */
    public UsdRate loadLatestUsdRate() {
        return jdbc.query(
                "SELECT rate_date, buy_rate, sell_rate FROM exchange_rate_history " +
                        "WHERE currency = 'USD' ORDER BY rate_date DESC LIMIT 1",
                (java.sql.ResultSet rs) -> rs.next()
                        ? new UsdRate(rs.getDate("rate_date").toLocalDate(),
                                      rs.getBigDecimal("buy_rate"), rs.getBigDecimal("sell_rate"))
                        : null);
    }

    /** USD/TWD 即期匯率快照（Task 180）。台銀被 WAF 擋時 USD 以 Yahoo 中間價暫定＝buy==sell。 */
    public record UsdRate(LocalDate rateDate, BigDecimal buyRate, BigDecimal sellRate) {}

    /**
     * 某美股指數最近兩個交易日收盤（{@code close}＝最新、{@code prevClose}＝前一交易日，供算漲跌%）。
     * 無資料回 null；只有一筆時 {@code prevClose} 為 null。
     */
    public UsIndexClose loadLatestUsIndexClose(String indexCode) {
        List<Object[]> rows = new java.util.ArrayList<>(2);
        // 第三引數用「void 區塊」lambda 才會解析為 RowCallbackHandler（逐列、rs 已定位）；
        // 若寫成 expression lambda（rows.add(...) 回 boolean）會被解析成 ResultSetExtractor（整段只呼一次、
        // rs 停在第一列前），rs.getDate 立即拋「ResultSet not positioned properly」。
        jdbc.query(
                "SELECT trading_date, close_point FROM us_index_daily_history " +
                        "WHERE index_code = ? ORDER BY trading_date DESC LIMIT 2",
                ps -> ps.setString(1, indexCode),
                (java.sql.ResultSet rs) -> {
                    rows.add(new Object[]{
                            rs.getDate("trading_date").toLocalDate(), rs.getBigDecimal("close_point")});
                });
        if (rows.isEmpty()) return null;
        return new UsIndexClose(indexCode,
                (LocalDate) rows.get(0)[0], (BigDecimal) rows.get(0)[1],
                rows.size() > 1 ? (BigDecimal) rows.get(1)[1] : null);
    }

    /** 美股指數收盤快照（Task 180）。 */
    public record UsIndexClose(String indexCode, LocalDate tradingDate,
                               BigDecimal close, BigDecimal prevClose) {}

    // ===== 公開資訊快照：海外參考個股（韓股三星/海力士，供 NewsPoller 組韓股快照 kr-market）=====

    /** Upsert 海外參考個股每日收盤（同一 stock_code+trading_date 視為覆寫）。 */
    public void upsertForeignStockDaily(String stockCode, LocalDate tradingDate, BigDecimal closePoint) {
        Long existing = jdbc.query(
                "SELECT 1 FROM foreign_stock_daily_history WHERE stock_code=? AND trading_date=?",
                ps -> { ps.setString(1, stockCode); ps.setObject(2, tradingDate); },
                rs -> rs.next() ? 1L : null);
        if (existing != null) {
            jdbc.update("UPDATE foreign_stock_daily_history SET close_point=? " +
                            "WHERE stock_code=? AND trading_date=?",
                    closePoint, stockCode, tradingDate);
        } else {
            jdbc.update("INSERT INTO foreign_stock_daily_history " +
                            "(stock_code, trading_date, close_point) VALUES (?, ?, ?)",
                    stockCode, tradingDate, closePoint);
        }
    }

    /**
     * 某海外參考個股最近兩個交易日收盤（{@code close}＝最新、{@code prevClose}＝前一交易日，供算漲跌%）。
     * 無資料回 null；只有一筆時 {@code prevClose} 為 null。
     */
    public ForeignStockClose loadLatestForeignStockClose(String stockCode) {
        List<Object[]> rows = new java.util.ArrayList<>(2);
        // 第三引數用「void 區塊」lambda 才會解析為 RowCallbackHandler（逐列、rs 已定位）；
        // 寫成 expression lambda 會被解析成 ResultSetExtractor（整段只呼一次、rs 未定位）→ runtime 才炸。
        jdbc.query(
                "SELECT trading_date, close_point FROM foreign_stock_daily_history " +
                        "WHERE stock_code = ? ORDER BY trading_date DESC LIMIT 2",
                ps -> ps.setString(1, stockCode),
                (java.sql.ResultSet rs) -> {
                    rows.add(new Object[]{
                            rs.getDate("trading_date").toLocalDate(), rs.getBigDecimal("close_point")});
                });
        if (rows.isEmpty()) return null;
        return new ForeignStockClose(stockCode,
                (LocalDate) rows.get(0)[0], (BigDecimal) rows.get(0)[1],
                rows.size() > 1 ? (BigDecimal) rows.get(1)[1] : null);
    }

    /** 海外參考個股收盤快照（韓股三星/海力士）。 */
    public record ForeignStockClose(String stockCode, LocalDate tradingDate,
                                    BigDecimal close, BigDecimal prevClose) {}
}
