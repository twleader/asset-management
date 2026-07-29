package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 257：{@link StockSourceQuery#collectHeldStockCodes} 的持股來源必須涵蓋
 * <b>每一位 owner</b> 各自的最新快照。
 *
 * <p>原實作取 {@code SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1}——全庫只取
 * 一筆。本系統為多租戶且 Requirement 35 每日把各 owner 的最新快照日期都釘成當日，於是多位 owner 的最新
 * 快照必然同日、tie-break 由 Postgres 任意決定。2026-07-29 實測 owner 1 的快照（35 筆台股持股）落選、
 * owner 2 的（2 筆）中選，使 {@code 2885}（只在 owner 1 持股、不在觀察清單）既不被每 2 分鐘的
 * {@code PricePoller.scheduledTwIntradayUpdate} 抓價、也不被 16:00 的
 * {@code ClosePersister.verifyTwCloseWithFinMind} 校正收盤，前端顯示前一交易日的假收盤。</p>
 *
 * <p>本檔釘住的是「不會被下游測試發現」的那一半：SQL 的 tie-break 具決定性、市場分流不混、
 * 觀察清單仍被聯集、以及無快照時不擲例外。</p>
 */
class StockSourceQueryHeldCodesTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StockSourceQuery query = new StockSourceQuery(jdbc);

    /** 一次 {@code jdbc.query(sql, RowCallbackHandler)} 呼叫的紀錄。 */
    private record Call(String sql, RowCallbackHandler handler) {}

    /**
     * 攔下所有 {@code jdbc.query(String, RowCallbackHandler)}，不驅動任何列。
     * 用於只關心 SQL 字串的案例。
     */
    private List<Call> captureQueries() {
        List<Call> calls = new ArrayList<>();
        doAnswer(inv -> {
            calls.add(new Call(inv.getArgument(0), inv.getArgument(1)));
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        return calls;
    }

    /** 建一個逐次回傳指定 (stock_code, market) 的 ResultSet 替身。 */
    private static ResultSet rowsOf(String code, String market) {
        ResultSet rs = mock(ResultSet.class);
        try {
            when(rs.getString("stock_code")).thenReturn(code);
            when(rs.getString("market")).thenReturn(market);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return rs;
    }

    // ── 257.3.1 SQL 契約 ────────────────────────────────────────────

    @Test
    void 持股查詢必須取每位owner各自的最新快照() {
        List<Call> calls = captureQueries();

        query.collectHeldStockCodes(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());

        String holdingSql = calls.stream()
                .map(Call::sql)
                .filter(s -> s.contains("stock_holding"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("沒有對 stock_holding 發出查詢"));

        assertThat(holdingSql).contains("DISTINCT ON (owner_user_id)");
    }

    /**
     * {@code id DESC} 是同 owner 同日多筆快照時的決定性 tie-break。少了它就退回本 bug 的
     * 「Postgres 任意挑一筆」，而那正是 2885 整天沒有行情的原因——沒有這條斷言，
     * 未來有人把它拿掉不會被任何測試發現。
     */
    @Test
    void 快照tiebreak必須具決定性() {
        List<Call> calls = captureQueries();

        query.collectHeldStockCodes(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());

        String holdingSql = calls.stream()
                .map(Call::sql)
                .filter(s -> s.contains("stock_holding"))
                .findFirst()
                .orElseThrow();

        assertThat(holdingSql)
                .contains("ORDER BY owner_user_id, snapshot_date DESC, id DESC");
    }

    /** 舊寫法（全庫單一最新快照）不得以任何形式留在查詢裡。 */
    @Test
    void 不得再出現全庫只取一筆快照的舊寫法() {
        List<Call> calls = captureQueries();
        // 舊實作另外走 jdbc.query(String, ResultSetExtractor) 取 latestSnapshotId；
        // 改寫後該呼叫應完全消失，故此處一併驗證它沒被呼叫過。
        query.collectHeldStockCodes(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());

        assertThat(calls).extracting(Call::sql)
                .noneMatch(s -> s.contains("ORDER BY snapshot_date DESC LIMIT 1"));
        verify(jdbc, org.mockito.Mockito.never())
                .query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class));
    }

    // ── 257.3.2 市場分流 ────────────────────────────────────────────

    @Test
    void 三個市場各自分流且互不混入() {
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            RowCallbackHandler h = inv.getArgument(1);
            if (sql.contains("stock_holding")) {
                h.processRow(rowsOf("2885", "台股"));
                h.processRow(rowsOf("VOO", "美股"));
                h.processRow(rowsOf("VWRA", "英股"));
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        query.collectHeldStockCodes(tw, us, uk);

        assertThat(tw).containsExactly("2885");
        assertThat(us).containsExactly("VOO");
        assertThat(uk).containsExactly("VWRA");
    }

    // ── 257.3.3 觀察清單仍被聯集、大盤仍被排除 ──────────────────────

    @Test
    void 觀察清單仍被聯集進來且大盤仍被排除() {
        List<String> sqls = new ArrayList<>();
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            sqls.add(sql);
            RowCallbackHandler h = inv.getArgument(1);
            if (sql.contains("stock_alert")) {
                h.processRow(rowsOf("2330", "台股"));
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        query.collectHeldStockCodes(tw, us, uk);

        assertThat(tw).contains("2330");
        assertThat(sqls).anyMatch(s -> s.contains("stock_alert")
                && s.contains("0000") && s.contains("台股"));
    }

    // ── 257.3.4 無快照時不擲例外 ────────────────────────────────────

    /**
     * 改寫移除了 {@code latestSnapshotId != null} 的守門（單一查詢不再需要它）。
     * 持股側零列時，方法必須正常返回，且 {@code stock_alert} 那段仍要照跑。
     */
    @Test
    void 完全沒有快照時不擲例外且觀察清單仍照跑() {
        List<String> sqls = new ArrayList<>();
        doAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            return null;   // 兩條查詢都回零列
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        query.collectHeldStockCodes(tw, us, uk);

        assertThat(tw).isEmpty();
        assertThat(us).isEmpty();
        assertThat(uk).isEmpty();
        assertThat(sqls).anyMatch(s -> s.contains("stock_alert"));
    }

    // ── 回歸錨點：Task 249 的雷達收集器不得被本次改動波及 ────────────

    @Test
    void 雷達收集器仍維持自己的每owner查詢與大盤排除() {
        List<Call> calls = captureQueries();

        Set<String> tw = new LinkedHashSet<>();
        query.collectTwRadarCodes(tw);

        assertThat(calls).extracting(Call::sql)
                .anyMatch(s -> s.contains("stock_holding")
                        && s.contains("DISTINCT ON (owner_user_id)"));
    }
}
