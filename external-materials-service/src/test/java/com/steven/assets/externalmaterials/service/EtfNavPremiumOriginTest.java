package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.EtfNavFetchClient.EtfNav;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 259：{@link EtfNavPoller#resolvePremium} 與 {@link StockSourceQuery#upsertEtfNav} 的
 * 折溢價來源標記（{@code pct_origin}）。
 *
 * <p>既有實作對「淨值有值、折溢價欄留白」一視同仁地以同日收盤價反推，台股也不例外——這違反
 * Requirement 34「折溢價原樣保存、不由淨值反推」，因證交所淨值欄在股票型 ETF 已四捨五入至小數 2 位，
 * 反推誤差達 0.07 個百分點。本檔釘住：台股缺漏一律留白不反推、美股反推並標記 {@code RECONSTRUCTED}、
 * 以及既有 {@code OFFICIAL} 值不得被反推值覆寫。</p>
 */
class EtfNavPremiumOriginTest {

    private static final LocalDate NAV_DATE = LocalDate.of(2026, 7, 30);

    @SuppressWarnings("unchecked")
    private static BiFunction<String, LocalDate, Optional<BigDecimal>> mockCloseLookup() {
        return mock(BiFunction.class);
    }

    // ── 259.5.1 台股、來源已有折溢價 ──────────────────────────────

    @Test
    void 台股來源已有折溢價時回OFFICIAL且不查收盤價() {
        EtfNav nav = new EtfNav("0050", "台股", new BigDecimal("93.50"),
                new BigDecimal("0.12"), "20260730 133000", "TWSE");
        BiFunction<String, LocalDate, Optional<BigDecimal>> closeLookup = mockCloseLookup();

        EtfNavPoller.PremiumResult result = EtfNavPoller.resolvePremium(nav, NAV_DATE, closeLookup);

        assertThat(result.pct()).isEqualByComparingTo("0.12");
        assertThat(result.origin()).isEqualTo("OFFICIAL");
        verifyNoInteractions(closeLookup);
    }

    // ── 259.5.2 台股、折溢價欄留白（核心回歸錨點）────────────────────

    /** 沒有它，未來有人把台股分支拿掉不會被任何測試發現——這正是本任務要修的 bug。 */
    @Test
    void 台股折溢價欄留白時不反推() {
        EtfNav nav = new EtfNav("00713", "台股", new BigDecimal("55.20"),
                null, "20260730 133000", "TWSE");
        BiFunction<String, LocalDate, Optional<BigDecimal>> closeLookup = mockCloseLookup();

        EtfNavPoller.PremiumResult result = EtfNavPoller.resolvePremium(nav, NAV_DATE, closeLookup);

        assertThat(result.pct()).isNull();
        assertThat(result.origin()).isNull();
        verifyNoInteractions(closeLookup);
    }

    // ── 259.5.3 美股、折溢價欄留白、收盤價可取得 ──────────────────────

    @Test
    void 美股折溢價欄留白時以同日收盤價反推並標記RECONSTRUCTED() {
        EtfNav nav = new EtfNav("VOO", "美股", new BigDecimal("500.00"),
                null, "2026-07-29", "Yahoo Finance");
        BiFunction<String, LocalDate, Optional<BigDecimal>> closeLookup = mockCloseLookup();
        when(closeLookup.apply("VOO", NAV_DATE)).thenReturn(Optional.of(new BigDecimal("501.50")));

        EtfNavPoller.PremiumResult result = EtfNavPoller.resolvePremium(nav, NAV_DATE, closeLookup);

        // (501.50 - 500.00) / 500.00 * 100 = 0.3000
        assertThat(result.pct()).isEqualByComparingTo("0.3000");
        assertThat(result.origin()).isEqualTo("RECONSTRUCTED");
    }

    // ── 259.5.4 美股、查無收盤價 ──────────────────────────────────

    @Test
    void 美股查無同日收盤價時回null() {
        EtfNav nav = new EtfNav("QQQ", "美股", new BigDecimal("480.00"),
                null, "2026-07-29", "Yahoo Finance");
        BiFunction<String, LocalDate, Optional<BigDecimal>> closeLookup = mockCloseLookup();
        when(closeLookup.apply("QQQ", NAV_DATE)).thenReturn(Optional.empty());

        EtfNavPoller.PremiumResult result = EtfNavPoller.resolvePremium(nav, NAV_DATE, closeLookup);

        assertThat(result.pct()).isNull();
        assertThat(result.origin()).isNull();
    }

    // ── 259.5.5 美股、淨值為 0 ────────────────────────────────────

    @Test
    void 美股淨值為零時回null不擲除以零例外() {
        EtfNav nav = new EtfNav("SGOV", "美股", BigDecimal.ZERO,
                null, "2026-07-29", "Yahoo Finance");
        BiFunction<String, LocalDate, Optional<BigDecimal>> closeLookup = mockCloseLookup();
        when(closeLookup.apply("SGOV", NAV_DATE)).thenReturn(Optional.of(new BigDecimal("100.00")));

        EtfNavPoller.PremiumResult result = EtfNavPoller.resolvePremium(nav, NAV_DATE, closeLookup);

        assertThat(result.pct()).isNull();
        assertThat(result.origin()).isNull();
    }

    // ── 259.5.6 upsertEtfNav 覆寫守門 ─────────────────────────────

    /** 既有列已是 OFFICIAL，本次要寫入 RECONSTRUCTED：折溢價與來源標記兩欄不得覆寫。 */
    @Test
    @SuppressWarnings("unchecked")
    void 既有OFFICIAL列不得被反推值覆寫() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.startsWith("SELECT id")),
                any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenReturn(42L);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.startsWith("SELECT pct_origin")),
                any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenReturn("OFFICIAL");
        StockSourceQuery query = new StockSourceQuery(jdbc);

        query.upsertEtfNav("0050", "台股", NAV_DATE,
                new BigDecimal("93.60"), new BigDecimal("0.30"), "RECONSTRUCTED", "TWSE");

        verify(jdbc).update(argThat((String sql) -> sql != null
                        && sql.contains("SET nav=?, source=?")
                        && !sql.contains("premium_discount_pct")
                        && !sql.contains("pct_origin")),
                any(), any(), any());
    }

    // ── 259.5.7 upsertEtfNav 正常寫入 ─────────────────────────────

    /** 既有列 pct_origin 為 null（或全新列）：三欄皆正常寫入，不觸發守門。 */
    @Test
    @SuppressWarnings("unchecked")
    void 既有列無pct_origin時正常寫入折溢價與來源標記() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.startsWith("SELECT id")),
                any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenReturn(42L);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.startsWith("SELECT pct_origin")),
                any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenReturn(null);
        StockSourceQuery query = new StockSourceQuery(jdbc);

        query.upsertEtfNav("VOO", "美股", NAV_DATE,
                new BigDecimal("500.00"), new BigDecimal("0.30"), "RECONSTRUCTED", "Yahoo Finance");

        verify(jdbc).update(argThat((String sql) -> sql != null
                        && sql.contains("premium_discount_pct=?")
                        && sql.contains("pct_origin=?")),
                any(), any(), any(), any(), any());
    }

    /** 全新列（尚無既有 id）：INSERT 分支帶入 pct_origin，不需守門判斷。 */
    @Test
    @SuppressWarnings("unchecked")
    void 全新列直接INSERT並帶入pct_origin() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.startsWith("SELECT id")),
                any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenReturn(null);
        StockSourceQuery query = new StockSourceQuery(jdbc);

        query.upsertEtfNav("IB01", "英股", NAV_DATE,
                new BigDecimal("100.00"), null, null, "TWSE");

        verify(jdbc).update(argThat((String sql) -> sql != null
                        && sql.startsWith("INSERT INTO etf_nav_history")
                        && sql.contains("pct_origin")),
                any(), any(), any(), any(), any(), any(), any());
    }
}
