package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.NewsRow;
import com.steven.assets.externalmaterials.client.TwseInfoFetchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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
@Slf4j
@RequiredArgsConstructor
public class StockSourceQuery {

    public static final String TWSE_MI_INDEX = "TWSE_MI_INDEX";
    public static final String TPEX_DAILY_CLOSE = "TPEX_DAILY_CLOSE";
    public static final String FINMIND_TW_CLOSE = "FINMIND_TW_CLOSE";
    public static final Set<String> TRUSTED_TW_CLOSE_SOURCES = Set.of(
            TWSE_MI_INDEX, TPEX_DAILY_CLOSE, FINMIND_TW_CLOSE);

    private static final String TW_RADAR_CODES_SQL = """
            WITH radar_codes AS (
                SELECT h.stock_code AS code
                FROM stock_holding h
                WHERE h.market = '台股'
                  AND h.snapshot_id IN (
                    SELECT DISTINCT ON (owner_user_id) id
                    FROM asset_snapshot
                    ORDER BY owner_user_id, snapshot_date DESC, id DESC
                  )
                UNION
                SELECT a.stock_code AS code
                FROM stock_alert a
                WHERE a.market = '台股'
            )
            SELECT DISTINCT s.code
            FROM stock s
            JOIN radar_codes r ON r.code = s.code
            WHERE s.market = '台股'
              AND s.code <> '0000'
              AND s.code ~ '^[0-9]{4,6}[A-Z]?$'
            ORDER BY s.code
            """;

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
     * 持股、不在觀察清單）既不被當時每 2 分鐘的 {@code PricePoller.scheduledTwIntradayUpdate} 抓價、
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
     * 今日交易雷達回補專用：**每位 owner 各自最新快照**的台股持股 ∪ 台股觀察清單，
     * 以台股 stock 主檔精確錨定、排除大盤 `0000` 與不合法代號（Task 424）。
     *
     * <p><b>為什麼不重用 {@link #collectHeldStockCodes}（以下為 Task 249 當時的狀態）：</b>後者當時取的是
     * {@code SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1}——
     * 全庫只取<b>一筆</b>，且日期相同時 tie-break 由 Postgres 任意決定。實測 2026-07-29 兩位 owner
     * 同日各有一筆快照（id 15 / owner 1，35 筆台股；id 18 / owner 2，2 筆台股），它挑中 id 18，
     * 於是 owner 1 只在自己持股、不在觀察清單的標的（實測 `2885`）永遠不會被回補——
     * 使用者按下「重新整理」卻有一列價格沒動，與按鈕的承諾不符。</p>
     *
     * <p><b>Task 257 起上段的前提已不成立</b>：{@link #collectHeldStockCodes} 現在用的是同一個
     * {@code DISTINCT ON (owner_user_id)} 子查詢。兩支<b>仍不可互相替換</b>，但理由換成了另外兩點：
     * 本方法在 SQL 層就 {@code WHERE h.market = '台股'} 過濾、並在方法尾端無條件
     * {@code remove("0000")}；{@link #collectHeldStockCodes} 則靠 {@code classify()} 的 else 分支把
     * 「非美股、非英股」的一切 market 值（含 NULL 與日後新增的市場類型）歸入台股，且只在
     * {@code stock_alert} 那條查詢排除 {@code 0000}。</p>
     *
     * <p>改用 {@code DISTINCT ON (owner_user_id) ... ORDER BY owner_user_id, snapshot_date DESC, id DESC}
     * 取每位 owner 的最新快照（同 owner 同日多筆時再以 id 決勝，結果具決定性）。範圍仍是全庫——
     * Redis 行情快取本就是跨租戶共用的市場資料，且回應不回傳任何檔數或代號，不構成租戶洩漏。</p>
     *
     * <p><b>Task 249 當時刻意不改 {@link #collectHeldStockCodes} 本身</b>：它當時服務每 2 分鐘的
     * {@code PricePoller.scheduledTwIntradayUpdate} 與 {@code refreshAll()}，放大其範圍會改變背景排程
     * 對外部 API 的請求量，屬另一個決定。現行台股 LIVE 由 dispatcher 以 input ∩ 交易雷達 effective set
     * 每 10 秒執行，見 Requirement 115／Task 380。</p>
     *
     * <p><b>（Task 257 已推翻上一段）</b>實測改為 per-owner 後台股僅 18 → 19 檔、美股與英股不變，
     * 請求量幾無變化；{@link #collectHeldStockCodes} 現已改為同一口徑。上一段保留為當時的決策記錄。</p>
     */
    public void collectTwRadarCodes(Set<String> twCodes) {
        jdbc.query(TW_RADAR_CODES_SQL,
                (java.sql.ResultSet rs) -> { twCodes.add(rs.getString("code")); });
    }

    /** 與台股雷達 SQL 的正規表達式完全相同，避免下游 Fubon consumer 擴張主檔錨定後的範圍。 */
    static boolean isTaiwanRadarCode(String code) {
        return code != null && code.matches("^[0-9]{4,6}[A-Z]?$") && !"0000".equals(code);
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

    /**
     * Upsert 台股大盤每日 OHLC ＋成交量（同一 trading_date 視為覆寫；OHLC／量欄任一者可為 null）。
     * tradeVolume／tradeValue 以 COALESCE 保值——FMTQIK 該次抓不到時傳 null，不可把 DB 既有值洗掉
     * （否則任何一次 FMTQIK 失效就會把已回補好的整月量欄靜默清成 null，畫面只表現為「柱子消失」）。
     */
    public void upsertTwseIndexDaily(LocalDate tradingDate,
                                      BigDecimal openPoint, BigDecimal highPoint,
                                      BigDecimal lowPoint, BigDecimal closePoint,
                                      Long tradeVolume, BigDecimal tradeValue) {
        Long existing = jdbc.query(
                "SELECT 1 FROM twse_index_daily_history WHERE trading_date=?",
                ps -> ps.setObject(1, tradingDate),
                rs -> rs.next() ? 1L : null);
        if (existing != null) {
            jdbc.update("UPDATE twse_index_daily_history " +
                            "SET open_point=?, high_point=?, low_point=?, close_point=?, " +
                            "trade_volume=COALESCE(?, trade_volume), trade_value=COALESCE(?, trade_value) " +
                            "WHERE trading_date=?",
                    openPoint, highPoint, lowPoint, closePoint, tradeVolume, tradeValue, tradingDate);
        } else {
            jdbc.update("INSERT INTO twse_index_daily_history " +
                            "(trading_date, open_point, high_point, low_point, close_point, trade_volume, trade_value) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    tradingDate, openPoint, highPoint, lowPoint, closePoint, tradeVolume, tradeValue);
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

    /**
     * Latest date and close from the same database row. Quote-cache synchronization must use this
     * single query so a concurrent official repair cannot pair an old price with a newly read date.
     */
    public record DatedClose(LocalDate date, BigDecimal close) {}

    public Optional<DatedClose> findLatestDatedClose(String stockCode, String market) {
        return Optional.ofNullable(jdbc.query(
                "SELECT trading_date, close_price FROM stock_price_history "
                        + "WHERE stock_code=? AND market=? AND close_price IS NOT NULL "
                        + "ORDER BY trading_date DESC LIMIT 1",
                ps -> {
                    ps.setString(1, stockCode);
                    ps.setString(2, market);
                },
                rs -> rs.next()
                        ? new DatedClose(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2))
                        : null));
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
     * 取某個股在該市場的<b>全部</b>日收盤（濾除 null 與非正收盤），由舊到新排序
     * （Requirement 74 / Task 334：美股歷史估值推導的交易日集合就是這張表的 {@code trading_date}）。
     *
     * <p>不設 {@code LIMIT}：推導序列要一次算完整段歷史（美股實測約 2,400 個交易日／檔），
     * 分頁取回反而會讓「可用區段起點」判斷不到真正的最早日。多列查詢的第三引數必須是
     * 「void 區塊」lambda（RowCallbackHandler），寫成 expression lambda 會被解析成 ResultSetExtractor。</p>
     */
    public List<ClosePoint> loadAllCloses(String stockCode, String market) {
        List<ClosePoint> rows = new java.util.ArrayList<>();
        jdbc.query(
                "SELECT trading_date, close_price FROM stock_price_history " +
                        "WHERE stock_code=? AND market=? AND close_price IS NOT NULL AND close_price > 0 " +
                        "ORDER BY trading_date",
                (java.sql.ResultSet rs) -> {
                    rows.add(new ClosePoint(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)));
                }, stockCode, market);
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

    /**
     * Upsert 原物料收盤價與來源 provenance。
     *
     * <p>同值重抓保留最早可得時間，只更新最近抓取時間；價格若修訂，則把可得時間推到本次抓取，
     * 讓歷史決策在舊值已被 mutable compatibility table 覆寫後 fail closed，而不是看見修訂後數字。
     * 舊列若尚未有 source_available_at 且本次價格相同，仍維持 null，交由 resolver 使用規格指定的
     * conservative legacy boundary。</p>
     */
    public void upsertCommodityPrice(
            String code,
            LocalDate priceDate,
            BigDecimal closePrice,
            String provider,
            String sourceUrl,
            Instant sourceAvailableAt,
            Instant fetchedAt) {
        Long existing = jdbc.query(
                "SELECT id FROM commodity_price_history WHERE commodity_code=? AND price_date=?",
                ps -> { ps.setString(1, code); ps.setObject(2, priceDate); },
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            jdbc.update(
                    "UPDATE commodity_price_history SET "
                            + "close_price=?, provider=?, source_url=?, "
                            + "source_available_at=CASE "
                            + "WHEN close_price IS DISTINCT FROM ? THEN ? "
                            + "ELSE source_available_at END, "
                            + "fetched_at=? WHERE id=?",
                    closePrice, provider, sourceUrl,
                    closePrice, timestamp(sourceAvailableAt), timestamp(fetchedAt), existing);
        } else {
            jdbc.update(
                    "INSERT INTO commodity_price_history "
                            + "(commodity_code,price_date,close_price,provider,source_url,"
                            + "source_available_at,fetched_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    code, priceDate, closePrice, provider, sourceUrl,
                    timestamp(sourceAvailableAt), timestamp(fetchedAt));
        }
    }

    /** Legacy compatibility for callers that have no provenance. */
    public void upsertCommodityPrice(String code, LocalDate priceDate, BigDecimal closePrice) {
        upsertCommodityPrice(code, priceDate, closePrice, null, null, null, null);
    }

    /** Append one typed BFI82U observation; no UPDATE path exists by design. */
    public void appendTwseInstitutionalObservation(
            TwseInfoFetchClient.InstitutionalObservation observation) {
        if (observation == null || observation.observedAt() == null
                || observation.provider() == null || observation.status() == null) {
            throw new IllegalArgumentException("institutional observation identity 缺漏");
        }
        jdbc.update(
                "INSERT INTO twse_institutional_daily "
                        + "(trading_date,foreign_net,trust_net,dealer_net,total_net,provider,source_url,"
                        + "observed_at,source_available_at,availability_basis,status,error_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (provider,observed_at) DO NOTHING",
                observation.tradingDate(), observation.foreignNet(), observation.trustNet(),
                observation.dealerNet(), observation.totalNet(), observation.provider(),
                observation.sourceUrl(), timestamp(observation.observedAt()),
                timestamp(observation.sourceAvailableAt()),
                observation.availabilityBasis(), observation.status(), observation.errorReason());
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
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
     *
     * <p><b>覆寫守門（Task 259）</b>：{@code pctOrigin} 標記這一筆折溢價是來源直接提供（{@code OFFICIAL}）
     * 還是本系統以收盤價反推（{@code RECONSTRUCTED}）。若既有列已是 {@code OFFICIAL}，本次寫入的是
     * {@code RECONSTRUCTED}，則 {@code premium_discount_pct}／{@code pct_origin} 兩欄<b>不覆寫</b>——
     * 正常抓取流程下不會發生（台股不再反推、美股從未產生 {@code OFFICIAL}），此守門是防禦未來新增
     * 資料來源（如 SITCA）時的誤用。{@code nav}／{@code source} 兩欄不受此守門影響，仍照常更新。
     */
    public void upsertEtfNav(String stockCode, String market, LocalDate navDate,
                             BigDecimal nav, BigDecimal premiumDiscountPct, String pctOrigin, String source) {
        Long existing = jdbc.query(
                "SELECT id FROM etf_nav_history WHERE stock_code=? AND market=? AND nav_date=?",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market); ps.setObject(3, navDate); },
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing == null) {
            jdbc.update("INSERT INTO etf_nav_history "
                    + "(stock_code, market, nav_date, nav, premium_discount_pct, pct_origin, source) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    stockCode, market, navDate, nav, premiumDiscountPct, pctOrigin, source);
            return;
        }
        String existingOrigin = jdbc.query(
                "SELECT pct_origin FROM etf_nav_history WHERE id=?",
                ps -> ps.setLong(1, existing),
                rs -> rs.next() ? rs.getString(1) : null);
        boolean blockOverwrite = "OFFICIAL".equals(existingOrigin) && "RECONSTRUCTED".equals(pctOrigin);
        if (blockOverwrite) {
            jdbc.update("UPDATE etf_nav_history SET nav=?, source=? WHERE id=?", nav, source, existing);
        } else {
            jdbc.update("UPDATE etf_nav_history SET nav=?, premium_discount_pct=?, pct_origin=?, source=? WHERE id=?",
                    nav, premiumDiscountPct, pctOrigin, source, existing);
        }
    }

    /**
     * Append one immutable ETF NAV observation for decision-time evidence.
     *
     * <p>This method intentionally contains no SELECT/UPDATE branch.  A repeated
     * identity is an idempotent no-op; a later fetch always has a new observed-at
     * and therefore remains a distinct revision in the audit stream.</p>
     */
    public void appendEtfNavObservation(
            String stockCode,
            String market,
            LocalDate navDate,
            BigDecimal nav,
            BigDecimal premiumDiscountPct,
            String pctOrigin,
            String source,
            Instant observedAt,
            Instant availableAt,
            String availabilityBasis) {
        if (stockCode == null || stockCode.isBlank() || market == null || market.isBlank()
                || navDate == null || nav == null || source == null || source.isBlank()
                || observedAt == null || availableAt == null
                || availabilityBasis == null || availabilityBasis.isBlank()) {
            throw new IllegalArgumentException("ETF NAV observation provenance 不完整");
        }
        Instant effectiveAvailableAt = availableAt.isBefore(observedAt) ? observedAt : availableAt;
        jdbc.update("INSERT INTO etf_nav_observation "
                        + "(stock_code,market,nav_date,nav,premium_discount_pct,pct_origin,source,"
                        + "observed_at,available_at,availability_basis) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                stockCode, market, navDate, nav, premiumDiscountPct, pctOrigin, source,
                java.sql.Timestamp.from(observedAt), java.sql.Timestamp.from(effectiveAvailableAt),
                availabilityBasis);
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
     *
     * @return {@code true} 表示實際寫入或更新了一列；{@code false} 表示因收盤價非正而被拒絕。
     *         呼叫端的成功計數一律以此回傳值為準，不得無條件累加。
     */
    public boolean upsertHistory(String stockCode, String market, LocalDate tradingDate,
                                 BigDecimal open, BigDecimal high, BigDecimal low,
                                 BigDecimal close, Long volume) {
        // 非正或缺漏的收盤價一律不寫（Requirement 62 / Task 279）。
        // 「沒有價」不得被轉成「價 0」——原本這裡對 close 套 nz() 正是這個反模式，已移除。
        // 刻意不擲例外：ClosePersister.dumpRedisToDb 逐檔 try/catch，擲出去只會被吃掉、
        // 留下一行看不出原因的 warn。DB 端另有 CHECK (close_price > 0) 作為最後一道。
        if (close == null || close.signum() <= 0) {
            log.warn("拒絕寫入非正收盤：{} {} {} close={}", market, stockCode, tradingDate, close);
            return false;
        }
        Long existing = jdbc.query(
                "SELECT id FROM stock_price_history WHERE stock_code=? AND market=? AND trading_date=?",
                ps -> { ps.setString(1, stockCode); ps.setString(2, market); ps.setObject(3, tradingDate); },
                rs -> rs.next() ? rs.getLong(1) : null);
        // open / high / low 一律保留 null（無資料），不再用 0 偽裝。
        if (existing != null) {
            jdbc.update(
                    "UPDATE stock_price_history SET open_price=?, high_price=?, low_price=?, close_price=?, volume=?, close_source=NULL WHERE id=?",
                    open, high, low, close, volume == null ? 0L : volume, existing);
        } else {
            jdbc.update(
                    "INSERT INTO stock_price_history (stock_code, market, trading_date, open_price, high_price, low_price, close_price, volume) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    stockCode, market, tradingDate,
                    open, high, low, close, volume == null ? 0L : volume);
        }
        return true;
    }

    /**
     * 寫入已驗證的收盤價與其可稽核來源。呼叫端必須已驗證來源日期恰等於 tradingDate。
     */
    public boolean upsertVerifiedHistory(String stockCode, String market, LocalDate tradingDate,
                                         BigDecimal open, BigDecimal high, BigDecimal low,
                                         BigDecimal close, Long volume, String closeSource) {
        if (close == null || close.signum() <= 0) {
            log.warn("拒絕寫入非正驗證收盤：{} {} {} close={}", market, stockCode, tradingDate, close);
            return false;
        }
        if (closeSource == null || closeSource.isBlank()) {
            log.warn("拒絕寫入缺少來源的驗證收盤：{} {} {}", market, stockCode, tradingDate);
            return false;
        }
        // Official close writers are authority writers: no SELECT-then-write race can let an older Fubon projection win.
        jdbc.update("""
                INSERT INTO stock_price_history (stock_code, market, trading_date, open_price, high_price, low_price, close_price, volume, close_source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (stock_code, market, trading_date) DO UPDATE SET
                  open_price=EXCLUDED.open_price, high_price=EXCLUDED.high_price, low_price=EXCLUDED.low_price,
                  close_price=EXCLUDED.close_price, volume=EXCLUDED.volume, close_source=EXCLUDED.close_source
                """, stockCode, market, tradingDate, open, high, low, close, volume == null ? 0L : volume, closeSource);
        return true;
    }

    /** Task425 guarded Fubon projection.  It cannot overwrite a trusted exchange/FinMind completed close. */
    public boolean upsertFubonHistoricalDailyCandle(String stockCode, LocalDate tradingDate,
                                                     BigDecimal open, BigDecimal high, BigDecimal low,
                                                     BigDecimal close, Long volume) {
        if (close == null || close.signum() <= 0) return false;
        int changed = jdbc.update("""
                INSERT INTO stock_price_history (stock_code, market, trading_date, open_price, high_price, low_price, close_price, volume, close_source)
                VALUES (?, '台股', ?, ?, ?, ?, ?, ?, 'FUBON_SDK')
                ON CONFLICT (stock_code, market, trading_date) DO UPDATE SET
                  open_price=EXCLUDED.open_price, high_price=EXCLUDED.high_price, low_price=EXCLUDED.low_price,
                  close_price=EXCLUDED.close_price, volume=EXCLUDED.volume, close_source='FUBON_SDK'
                WHERE stock_price_history.close_source IS NULL OR stock_price_history.close_source NOT IN
                  ('TWSE_MI_INDEX', 'TPEX_DAILY_CLOSE', 'FINMIND_TW_CLOSE')
                """, stockCode, tradingDate, open, high, low, close, volume == null ? 0L : volume);
        return changed == 1;
    }

    /** 台股指定日是否已有可信來源的完成收盤。 */
    public boolean hasTrustedTwClose(String stockCode, LocalDate tradingDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_price_history " +
                        "WHERE stock_code=? AND market='台股' AND trading_date=? " +
                        "AND close_source IN ('TWSE_MI_INDEX','TPEX_DAILY_CLOSE','FINMIND_TW_CLOSE')",
                Integer.class, stockCode, tradingDate);
        return count != null && count > 0;
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

    /**
     * Stores the latest intraday observation independently from the authoritative daily close.
     * PostgreSQL performs the freshness decision so concurrent poller rounds cannot regress a quote.
     */
    @Transactional
    public IntradayPersistenceResult persistIntradayQuote(
            com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult quote) {
        if (quote == null || quote.stockCode() == null || quote.market() == null
                || quote.tradingDate() == null || quote.freshnessInstant() == null
                || quote.price() == null || quote.price().signum() <= 0) {
            return IntradayPersistenceResult.failed();
        }
        try {
            List<String> applied = jdbc.query("""
                    INSERT INTO stock_intraday_quote
                      (stock_code, market, trading_date, provider_updated_at, source, actual_price,
                       previous_close, open_price, high_price, low_price, buy_price, sell_price, volume)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (stock_code, market) DO UPDATE SET
                      trading_date=EXCLUDED.trading_date, provider_updated_at=EXCLUDED.provider_updated_at,
                      source=EXCLUDED.source, actual_price=EXCLUDED.actual_price,
                      previous_close=EXCLUDED.previous_close, open_price=EXCLUDED.open_price,
                      high_price=EXCLUDED.high_price, low_price=EXCLUDED.low_price,
                      buy_price=EXCLUDED.buy_price, sell_price=EXCLUDED.sell_price, volume=EXCLUDED.volume
                    WHERE EXCLUDED.provider_updated_at > stock_intraday_quote.provider_updated_at
                    RETURNING stock_code, market, trading_date, provider_updated_at, source, actual_price,
                      previous_close, open_price, high_price, low_price, buy_price, sell_price, volume
                    """, (rs, rowNum) -> rs.getString("stock_code"),
                    quote.stockCode(), quote.market(), quote.tradingDate(), java.sql.Timestamp.from(quote.freshnessInstant()), quote.source(),
                    quote.price(), quote.previousClose(), quote.openPrice(), quote.highPrice(), quote.lowPrice(),
                    quote.buyPrice(), quote.sellPrice(), quote.volume());
            List<IntradayQuote> canonical = jdbc.query("""
                    SELECT q.stock_code, q.market, q.trading_date, q.provider_updated_at, q.source, q.actual_price,
                      q.previous_close, q.open_price, q.high_price, q.low_price, q.buy_price, q.sell_price, q.volume,
                      s.name AS stock_name
                    FROM stock_intraday_quote q LEFT JOIN stock s ON s.code=q.stock_code AND s.market=q.market
                    WHERE q.stock_code=? AND q.market=?
                    """, (rs, rowNum) -> readIntradayQuote(rs), quote.stockCode(), quote.market());
            return canonical.isEmpty()
                    ? IntradayPersistenceResult.failed()
                    : (applied.isEmpty() ? IntradayPersistenceResult.stale(canonical.getFirst())
                    : IntradayPersistenceResult.applied(canonical.getFirst()));
        } catch (Exception ex) {
            log.warn("盤中 snapshot 寫入失敗 market={} code={}", quote.market(), quote.stockCode(), ex);
            return IntradayPersistenceResult.failed();
        }
    }

    private static IntradayQuote readIntradayQuote(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IntradayQuote(rs.getString("stock_code"), rs.getString("market"),
                rs.getObject("trading_date", LocalDate.class), rs.getTimestamp("provider_updated_at").toInstant(),
                rs.getString("source"), rs.getBigDecimal("actual_price"), rs.getBigDecimal("previous_close"),
                rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"), rs.getBigDecimal("low_price"),
                rs.getBigDecimal("buy_price"), rs.getBigDecimal("sell_price"),
                rs.getObject("volume", Long.class), rs.getString("stock_name"));
    }

    public record IntradayQuote(String stockCode, String market, LocalDate tradingDate, Instant updatedAt,
                                String source, BigDecimal actualPrice, BigDecimal previousClose,
                                BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
                                BigDecimal buyPrice, BigDecimal sellPrice, Long volume, String stockName) {
        public com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult asPriceResult() {
            return new com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult(stockCode, market,
                    actualPrice, null, null, source, stockName, buyPrice, sellPrice, openPrice, previousClose,
                    highPrice, lowPrice, volume, tradingDate, updatedAt);
        }
    }

    public record IntradayPersistenceResult(Status status, IntradayQuote canonical) {
        public enum Status { APPLIED, STALE_OR_EQUAL, FAILED }
        static IntradayPersistenceResult applied(IntradayQuote quote) { return new IntradayPersistenceResult(Status.APPLIED, quote); }
        static IntradayPersistenceResult stale(IntradayQuote quote) { return new IntradayPersistenceResult(Status.STALE_OR_EQUAL, quote); }
        static IntradayPersistenceResult failed() { return new IntradayPersistenceResult(Status.FAILED, null); }
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

    // nz(BigDecimal) 已移除（Requirement 62 / Task 279）：它唯一的使用者是 upsertHistory 的
    // close 欄，作用是把 null 轉成 0 以滿足 NOT NULL——正是「沒有價就寫價 0」的反模式。
    // 現在非正／null 的 close 一律拒寫，不需要這個轉換。

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
