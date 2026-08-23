package com.steven.assets.service;

import com.steven.assets.model.DividendDates;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.repository.JdbcDividendCurrentStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 357／357.2c：<b>錨定日是 {@code min(除息日, 除權日)}，不是 {@code coalesce}</b>。
 *
 * <p>Requirement 94 的 357.2c 明文定義 {@code anchorDate = min(除息日, 除權日)}，理由是
 * 「取較早者才不會讓事件落在區間外而漏抓」；357.3d-1 那句 {@code COALESCE(...)}
 * 「即 anchorDate 的落地形式」是規格自身的內部矛盾。實測（PostgreSQL 17）：</p>
 * <pre>
 * LEAST('2026-09-05','2026-08-28')    = 2026-08-28   -- 較早者
 * COALESCE('2026-09-05','2026-08-28') = 2026-09-05   -- 第一個非 null
 * -- 視窗 2026-08-20 ~ 08-31：LEAST 命中、COALESCE 漏抓
 * </pre>
 *
 * <p>兩者只有在「至多一個非 null」時才等價，所以<b>光靠純配股（除息日 null）的案例
 * 抓不到這個錯</b>——本檔的每一條斷言都刻意讓兩欄同時有值且<b>除權日較早</b>，
 * 那是唯一能區分兩種語意的形狀。</p>
 *
 * <p>SQL 側沒有 H2、Testcontainers 需要 Docker，故以「捕捉真正送出去的 SQL 字串」
 * 釘住 {@code LEAST}；那是本專案在無 in-memory DB 的情況下唯一能防止它被改回
 * {@code COALESCE} 的方式。</p>
 */
class DividendAnchorDateLeastTest {

    private static final LocalDate EX_DIVIDEND = LocalDate.of(2026, 9, 5);
    private static final LocalDate EX_RIGHTS = LocalDate.of(2026, 8, 28);

    // ── Java 側：唯一那份算術 ────────────────────────────────────────────────

    @Test
    @DisplayName("357.2c anchorDate 取較早者；只有一個日期時等於該日期；兩者皆 null 回 null")
    void anchorDateIsTheEarlierOfTheTwoDates() {
        assertThat(DividendDates.anchorDate(EX_DIVIDEND, EX_RIGHTS))
                .as("兩欄皆有值時取較早者；coalesce 會在這裡取到較晚的除息日 %s", EX_DIVIDEND)
                .isEqualTo(EX_RIGHTS);
        assertThat(DividendDates.anchorDate(EX_RIGHTS, EX_DIVIDEND))
                .as("順序相反時結果必須一致——min 不看欄位順序")
                .isEqualTo(EX_RIGHTS);
        assertThat(DividendDates.anchorDate(EX_DIVIDEND, null))
                .as("只有除息日（純現金事件，拆欄前的既有資料）行為逐位不變")
                .isEqualTo(EX_DIVIDEND);
        assertThat(DividendDates.anchorDate(null, EX_RIGHTS))
                .as("只有除權日（純配股事件）")
                .isEqualTo(EX_RIGHTS);
        assertThat(DividendDates.anchorDate(null, null))
                .as("兩欄皆空的年度彙總列沒有錨定日")
                .isNull();
    }

    @Test
    @DisplayName("357.2c backend 內所有 anchorDate() 進入點都委派同一份算術")
    void everyBackendEntryPointDelegatesToTheSameArithmetic() {
        StockDividendHistory entity = StockDividendHistory.builder()
                .exDividendDate(EX_DIVIDEND).exRightsDate(EX_RIGHTS).build();
        assertThat(entity.anchorDate())
                .as("StockDividendHistory#anchorDate()")
                .isEqualTo(EX_RIGHTS);

        assertThat(new DividendCurrentStateRepository.Event(
                "k", 2026, EX_DIVIDEND, BigDecimal.ONE, BigDecimal.ONE, null, null, EX_RIGHTS)
                .anchorDate())
                .as("DividendCurrentStateRepository.Event#anchorDate()")
                .isEqualTo(EX_RIGHTS);

        assertThat(new DividendCurrentStateRepository.ProjectedEvent(
                "k", 2026, EX_DIVIDEND, BigDecimal.ONE, BigDecimal.ONE, null, null, EX_RIGHTS)
                .anchorDate())
                .as("DividendCurrentStateRepository.ProjectedEvent#anchorDate()")
                .isEqualTo(EX_RIGHTS);

        assertThat(new DividendCurrentStateRepository.ActiveFutureEvent(
                1L, "k", 2026, EX_DIVIDEND, BigDecimal.ONE, BigDecimal.ONE, null, null, EX_RIGHTS)
                .anchorDate())
                .as("DividendCurrentStateRepository.ActiveFutureEvent#anchorDate()")
                .isEqualTo(EX_RIGHTS);

        assertThat(new DividendCurrentStateRepository.ActiveEventDetail(
                1L, "k", 2026, EX_DIVIDEND, BigDecimal.ONE, BigDecimal.ONE, null, null,
                null, null, null, EX_RIGHTS)
                .anchorDate())
                .as("DividendCurrentStateRepository.ActiveEventDetail#anchorDate()")
                .isEqualTo(EX_RIGHTS);

        assertThat(new DividendEventEvidenceResolver.Event(
                EX_DIVIDEND, BigDecimal.ONE, BigDecimal.ONE, null, null,
                Instant.parse("2026-08-01T00:00:00Z"), "FINMIND", List.of(), EX_RIGHTS)
                .anchorDate())
                .as("DividendEventEvidenceResolver.Event#anchorDate()")
                .isEqualTo(EX_RIGHTS);
    }

    @Test
    @DisplayName("357.3d-1 錨定日落在視窗內、除息日落在視窗外時，事件必須被視為在視窗內")
    void anEventWhoseExRightsDateFallsInsideTheWindowIsNotSilentlyDropped() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 31);
        LocalDate anchor = DividendDates.anchorDate(EX_DIVIDEND, EX_RIGHTS);

        assertThat(anchor != null && !anchor.isBefore(from) && !anchor.isAfter(to))
                .as("這正是 COALESCE 版本會漏抓的形狀：coalesce 取到 %s 落在 %s~%s 之外",
                        EX_DIVIDEND, from, to)
                .isTrue();
    }

    // ── SQL 側：LEAST，且不得誤傷 uk_dividend_event 的 null-sentinel ────────────

    @Test
    @DisplayName("357.3d-1b findActiveFutureEvents 的三處區間比較必須用 LEAST，不得是 COALESCE")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void activeFutureEventQueryComparesOnLeastNotCoalesce() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        new JdbcDividendCurrentStateRepository(jdbc).findActiveFutureEvents(
                "2885", "台股", LocalDate.of(2026, 8, 9),
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23));

        String sql = capturedSql(jdbc, "FROM stock_dividend_history");
        assertThat(sql)
                .as("三處區間比較都必須套在錨定日上")
                .contains("AND LEAST(ex_dividend_date, ex_rights_date)>?")
                .contains("AND LEAST(ex_dividend_date, ex_rights_date)>=?")
                .contains("AND LEAST(ex_dividend_date, ex_rights_date)<=?");
        assertThat(sql)
                .as("COALESCE 取第一個非 null，兩欄皆有值且除權日較早時會把落在 scope 內的事件擋掉")
                .doesNotContain("COALESCE(ex_dividend_date, ex_rights_date)");
    }

    @Test
    @DisplayName("357.3d-1b findActiveEventDetails 的存在性過濾必須用 LEAST")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void activeEventDetailQueryFiltersOnLeast() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        new JdbcDividendCurrentStateRepository(jdbc).findActiveEventDetails("2885", "台股");

        String sql = capturedSql(jdbc, "FROM stock_dividend_history");
        assertThat(sql)
                .contains("AND LEAST(ex_dividend_date, ex_rights_date) IS NOT NULL")
                .doesNotContain("COALESCE(ex_dividend_date, ex_rights_date)");
        assertThat(sql)
                .as("不得殘留「除息日為 null 就排除」的舊語意")
                .doesNotContain("AND ex_dividend_date IS NOT NULL");
    }

    @Test
    @DisplayName("357.1 uk_dividend_event 的 null-sentinel 比對必須維持 COALESCE，不得被改成 LEAST")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reconciliationKeepsTheUniqueIndexNullSentinelConvention() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet currentRow = mock(ResultSet.class);
        when(currentRow.getString("stock_code")).thenReturn("2885");
        when(currentRow.getString("market")).thenReturn("台股");
        when(currentRow.getObject("year", Integer.class)).thenReturn(2026);
        when(currentRow.getObject("ex_dividend_date", LocalDate.class)).thenReturn(null);
        when(currentRow.getObject("ex_rights_date", LocalDate.class)).thenReturn(EX_RIGHTS);
        when(currentRow.getBigDecimal("cash_dividend")).thenReturn(BigDecimal.ZERO);
        when(currentRow.getBigDecimal("stock_dividend")).thenReturn(new BigDecimal("0.30"));
        when(currentRow.getObject("cash_payment_date", LocalDate.class)).thenReturn(null);
        when(currentRow.getObject("stock_payment_date", LocalDate.class)).thenReturn(null);
        when(jdbc.query(contains("SELECT id FROM stock_dividend_history"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(42L));
        when(jdbc.query(contains("SELECT stock_code, market, year"),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> List.of(((RowMapper) inv.getArgument(1)).mapRow(currentRow, 0)));

        new JdbcDividendCurrentStateRepository(jdbc).upsertActiveEvent(
                "2885", "台股", "FINMIND",
                new DividendCurrentStateRepository.ProjectedEvent(
                        "k", 2026, null, BigDecimal.ZERO, new BigDecimal("0.30"),
                        null, null, EX_RIGHTS));

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(written.capture(), any(Object[].class));
        ArgumentCaptor<String> read = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).query(read.capture(), any(RowMapper.class), any(Object[].class));
        String all = String.join("\n", written.getAllValues())
                + "\n" + String.join("\n", read.getAllValues());
        assertThat(all)
                .as("兩個日期欄各自對 '1970-01-01' 做 null-sentinel 比對（tuple-twin 查詢與"
                        + " CANCELLED tombstone 刪除），改成 LEAST 會把兩欄壓成一個值")
                .contains("COALESCE(ex_dividend_date, DATE '1970-01-01')")
                .contains("COALESCE(ex_rights_date, DATE '1970-01-01')");
        assertThat(all)
                .as("null-sentinel 段落不得出現跨兩欄的 LEAST")
                .doesNotContain("LEAST(ex_dividend_date, ex_rights_date)");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String capturedSql(JdbcTemplate jdbc, String marker) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return sql.getAllValues().stream().filter(s -> s.contains(marker))
                .findFirst().orElseThrow();
    }
}
