package com.steven.assets.repository;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.service.DividendEventEvidenceResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 357.3d-1 ＋ 357.6a：<b>交易雷達「下一配息」對只配股事件的存活性</b>。
 *
 * <p>拆欄後只配股的事件 {@code ex_dividend_date} 是 {@code null}，它必須：</p>
 * <ol>
 *   <li>通過 {@link JdbcDividendEventEvidenceRepository} 的入口過濾
 *       （{@code LEAST(ex_dividend_date, ex_rights_date) IS NOT NULL}）；</li>
 *   <li>被 {@link DividendEventEvidenceResolver} 以 {@code anchorDate} 認定為未來事件；</li>
 *   <li>在 {@code TradingRadarDto.RadarEvidence} 投影時<b>不 NPE</b>，且
 *       {@code nextDistributionDate} 等於它的除權日（＝ anchorDate）。</li>
 * </ol>
 *
 * <p><b>第 3 點在 Task 357 之前不是理論風險。</b>{@code RadarEvidence.withConfidence} 直接寫
 * {@code nextEvent().exDividendDate().toString()}；它至今不 NPE <b>只因為</b> repository 那一層
 * 有 {@code AND ex_dividend_date IS NOT NULL} 把這類事件擋在門外。放寬過濾的同一刻，
 * 那一行就會炸。</p>
 *
 * <p>本檔用 mock 的 {@link JdbcTemplate}／{@link ResultSet}（比照既有的
 * {@code JdbcDividendEvidencePipelineTest}）跑<b>真正的 production SQL 字串與 row mapper</b>，
 * 因此不需要 Docker 或資料庫。WHERE 子句本身無法被 mock 執行，故另以字串斷言釘住——
 * 那是本專案在沒有 H2 的情況下唯一能防止它被改回去的方式。</p>
 */
class JdbcDividendEvidenceExRightsTest {

    private static final LocalDate EX_RIGHTS = LocalDate.of(2026, 8, 20);
    private static final Instant OBSERVED = Instant.parse("2026-08-09T12:01:00Z");
    private static final Instant DECISION = Instant.parse("2026-08-09T13:00:00Z");

    @Test
    @DisplayName("357.3d-1 只配股事件（除息日 null、除權日有值）仍出現在「下一配息」")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stockOnlyEventStillResolvesAsTheNextDistribution() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubSnapshot(jdbc);
        stubStockOnlyEvent(jdbc);

        var resolution = new JdbcDividendEventEvidenceRepository(jdbc)
                .resolve("2881", "台股", DECISION, sessions20());

        assertThat(resolution.status())
                .as("只配股的事件不得因為除息日為 null 而從下一配息消失")
                .isEqualTo(DividendEventEvidenceResolver.Status.AVAILABLE);
        assertThat(resolution.nextEvent().exDividendDate())
                .as("拆欄後除息日必須誠實地是 null，不得拿除權日頂替")
                .isNull();
        assertThat(resolution.nextEvent().exRightsDate()).isEqualTo(EX_RIGHTS);
        assertThat(resolution.nextEvent().anchorDate()).isEqualTo(EX_RIGHTS);
        assertThat(resolution.eventsWithinFiveSessions()).isEqualTo(0);
        assertThat(resolution.eventsWithinTwentySessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("357.6a 只配股事件投影成 RadarEvidence 不得 NPE，nextDistributionDate ＝ anchorDate")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void radarEvidenceProjectionSurvivesANullExDividendDate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubSnapshot(jdbc);
        stubStockOnlyEvent(jdbc);
        var resolution = new JdbcDividendEventEvidenceRepository(jdbc)
                .resolve("2881", "台股", DECISION, sessions20());

        assertThatCode(() -> TradingRadarDto.RadarEvidence.withConfidence(
                null, null, "MISSING", false, null, null, null, null, null, false,
                null, List.of(), null, null, null, resolution))
                .as("除息日為 null 時 RadarEvidence 投影不得 NPE")
                .doesNotThrowAnyException();

        var evidence = TradingRadarDto.RadarEvidence.withConfidence(
                null, null, "MISSING", false, null, null, null, null, null, false,
                null, List.of(), null, null, null, resolution);
        assertThat(evidence.nextDistributionDate())
                .as("nextDistributionDate 的語意是 anchorDate；只配股的事件即為除權日")
                .isEqualTo(EX_RIGHTS.toString());
        assertThat(evidence.nextDistributionStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("357.3d-1 snapshot event 查詢必須以錨定日取代 ex_dividend_date IS NOT NULL")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void snapshotEventQueryUsesCoalesceGuard() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubSnapshot(jdbc);
        stubStockOnlyEvent(jdbc);
        new JdbcDividendEventEvidenceRepository(jdbc)
                .resolve("2881", "台股", DECISION, sessions20());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        String eventSql = sql.getAllValues().stream()
                .filter(s -> s.contains("FROM stock_dividend_snapshot_event"))
                .findFirst().orElseThrow();

        assertThat(eventSql)
                .as("事件必須連 ex_rights_date 一起讀出來，否則 resolver 拿不到錨定日")
                .contains("ex_rights_date");
        assertThat(eventSql)
                .as("入口過濾必須放寬為錨定日非 null；LEAST 與 Java 端 anchorDate 同語意，"
                        + "全路徑統一寫法，不得一邊 LEAST 一邊 COALESCE")
                .contains("LEAST(ex_dividend_date, ex_rights_date) IS NOT NULL");
        assertThat(eventSql)
                .as("不得殘留「除息日為 null 就排除」的舊語意")
                .doesNotContain("AND ex_dividend_date IS NOT NULL");
    }

    // ── 共用 stub ──────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubSnapshot(JdbcTemplate jdbc) {
        try {
            ResultSet snapshot = mock(ResultSet.class);
            when(snapshot.getLong("id")).thenReturn(7L);
            when(snapshot.getString("provider")).thenReturn("FINMIND");
            when(snapshot.getObject("scope_from", LocalDate.class))
                    .thenReturn(LocalDate.of(2026, 8, 9));
            when(snapshot.getObject("scope_to", LocalDate.class))
                    .thenReturn(LocalDate.of(2026, 9, 23));
            when(snapshot.getTimestamp("observed_at")).thenReturn(Timestamp.from(OBSERVED));
            when(snapshot.getTimestamp("source_available_at")).thenReturn(Timestamp.from(OBSERVED));
            when(snapshot.getString("status")).thenReturn("COMPLETE");
            when(snapshot.getBoolean("complete")).thenReturn(true);
            when(jdbc.query(contains("FROM stock_dividend_snapshot s"), any(RowMapper.class),
                    any(Object[].class)))
                    .thenAnswer(inv -> List.of(((RowMapper) inv.getArgument(1)).mapRow(snapshot, 0)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 2881 型的純配股事件：除息日 null、除權日有值、現金股利 0。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubStockOnlyEvent(JdbcTemplate jdbc) {
        try {
            ResultSet event = mock(ResultSet.class);
            when(event.getLong("snapshot_id")).thenReturn(7L);
            when(event.getTimestamp("source_available_at")).thenReturn(null);
            when(event.getObject("ex_dividend_date", LocalDate.class)).thenReturn(null);
            when(event.getObject("ex_rights_date", LocalDate.class)).thenReturn(EX_RIGHTS);
            when(event.getBigDecimal("cash_dividend")).thenReturn(BigDecimal.ZERO);
            when(event.getBigDecimal("stock_dividend")).thenReturn(new BigDecimal("0.25"));
            when(event.getObject("cash_payment_date", LocalDate.class)).thenReturn(null);
            when(event.getObject("stock_payment_date", LocalDate.class)).thenReturn(null);
            when(jdbc.query(contains("FROM stock_dividend_snapshot_event"), any(RowMapper.class),
                    any(Object[].class)))
                    .thenAnswer(inv -> List.of(((RowMapper) inv.getArgument(1)).mapRow(event, 0)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<LocalDate> sessions20() {
        List<LocalDate> out = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 8, 10);
        while (out.size() < 20) {
            if (date.getDayOfWeek().getValue() <= 5) out.add(date);
            date = date.plusDays(1);
        }
        return out;
    }
}
