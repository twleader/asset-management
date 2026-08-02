package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Task 279：{@link StockSourceQuery#upsertHistory} 的非正收盤守門（縱深防禦第二道）。
 *
 * <p>成因是來源對「當日無整股成交」不發布 OHLC、FinMind 序列化為 {@code 0.0}。主要修正在
 * fetch 端，但 {@code upsertHistory} 有 8 個呼叫點（{@code ClosePersister} 4、
 * {@code HistoricalBackfillService} 4），此關同時保護排程收盤 dump 與歷史回補。
 *
 * <p>原實作對 close 套 {@code nz()}（null → {@code BigDecimal.ZERO}）以滿足 NOT NULL，
 * 正是「沒有價就寫價 0」的反模式，已一併移除。
 *
 * <p>本模組沒有 H2／Testcontainers，故以 mock 的 {@link JdbcTemplate} 斷言「完全沒有下任何
 * INSERT/UPDATE」，而不是真的寫一列再查回來。
 */
class StockSourceQueryNonPositiveCloseTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StockSourceQuery query = new StockSourceQuery(jdbc);

    private boolean upsert(BigDecimal close, Long volume) {
        return query.upsertHistory("006208", "台股", LocalDate.of(2016, 8, 3),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, close, volume);
    }

    /** 完全無成交（127/175 列屬此型）：close 與 volume 皆 0。 */
    @Test
    void zeroClose_isRejected_andNothingWritten() {
        assertThat(upsert(BigDecimal.ZERO, 0L)).isFalse();
        verifyNoWrite();
    }

    /**
     * 有成交量但仍無 OHLC（48/175 列屬此型，如 006208 2017-03-28 成交 113 股）。
     * 判準必須是 close，不是 volume——用 volume 當判準會漏掉這一型。
     */
    @Test
    void zeroCloseWithVolume_isRejected_andNothingWritten() {
        assertThat(upsert(BigDecimal.ZERO, 113L)).isFalse();
        verifyNoWrite();
    }

    @Test
    void negativeClose_isRejected_andNothingWritten() {
        assertThat(upsert(new BigDecimal("-1.5"), 100L)).isFalse();
        verifyNoWrite();
    }

    /** null close 不得再被 nz() 轉成 0 寫入，一律拒絕。 */
    @Test
    void nullClose_isRejected_andNothingWritten() {
        assertThat(upsert(null, 100L)).isFalse();
        verifyNoWrite();
    }

    /**
     * 不得擲例外：{@code ClosePersister.dumpRedisToDb} 逐檔 try/catch，擲出去只會被吃掉、
     * 留下一行看不出原因的 warn。
     */
    @Test
    void rejection_doesNotThrow() {
        assertThatCode(() -> upsert(BigDecimal.ZERO, 0L)).doesNotThrowAnyException();
        assertThatCode(() -> upsert(null, null)).doesNotThrowAnyException();
    }

    /** 正常收盤仍照寫，且回 true——守門不得誤傷合法資料。 */
    @Test
    void positiveClose_isWritten() {
        assertThat(upsert(new BigDecimal("39.60"), 3000L)).isTrue();
        verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 連 SELECT 既有 id 都不該發生：拒絕要在查詢之前就短路。 */
    private void verifyNoWrite() {
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(jdbc, never()).query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class));
    }
}
