package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 357／357.3a-0：canonicalEvent() 新增 exRightsDate 後，既有
 * {@code stock_dividend_snapshot_event.event_key} 全數漂移，{@link DividendEventKeyMigration}
 * 是唯一的一次性重算機制。這裡驗證的是「backend {@code DividendCurrentStateProjectionService}
 * 以 event_key 建立 identity」所依賴的不變量本身——即使無法在本模組內驅動 backend 類別，
 * 「遷移後 stored event_key 收斂到與新公式重算結果一致」這件事一旦不成立，backend 那一側的
 * identity join 必然對不上，因此把它釘在能被外部（backend）依賴的邊界上。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class DividendEventKeyMigrationTest {

    @Test
    void migrationRecomputesStaleEventKeyToConvergeWithFreshCanonicalHash() throws SQLException {
        // RED（遷移前）：既有列的 event_key 是 357 之前的舊公式雜湊，與新公式（納入
        // exRightsDate）重算結果必然不同——若不遷移，backend 以 "key:"+eventKey 建立的
        // identity 會對不上，這一列會被誤判為「新 snapshot 未含舊 event」而 CANCELLED。
        long id = 1L;
        String staleEventKey = "PRE_357_LEGACY_EVENT_KEY_WITHOUT_EX_RIGHTS_DATE";
        var reconstructed = new DividendFetchClient.DividendEvent(
                2022, BigDecimal.ZERO, new BigDecimal("0.30"), "2022-09-22", null, null, null);
        String freshHash = DividendSnapshotStore.canonicalEventHash(reconstructed);
        assertThat(staleEventKey).isNotEqualTo(freshHash);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mockRow(id, staleEventKey, 2022, "2022-09-22", null,
                BigDecimal.ZERO, new BigDecimal("0.30"), null, null);
        stubSingleRow(jdbc, row);

        new DividendEventKeyMigration(jdbc).migrateEventKeys();

        List<Object[]> updates = captureBatchUpdate(jdbc);
        // GREEN（遷移後）：event_key 已更新為與新公式一致的雜湊，之後任何以相同欄位值
        // 重算 canonicalEventHash() 都會得到相同結果，identity 得以延續、不再漂移。
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0)[0]).isEqualTo(freshHash);
        assertThat(updates.get(0)[1]).isEqualTo(id);
    }

    @Test
    void migrationIsIdempotentWhenEventKeyAlreadyMatchesNewFormula() throws SQLException {
        // 冪等性：模擬「已在新版 canonicalEvent() 之下重新 sync 過」的列，其 event_key
        // 已經是新公式雜湊——重跑遷移不得再次更新，否則重跑不安全（每次啟動都要跑一次）。
        var event = new DividendFetchClient.DividendEvent(
                2025, BigDecimal.ZERO, new BigDecimal("0.30"), null, "2025-09-25", null, null);
        String alreadyCurrentKey = DividendSnapshotStore.canonicalEventHash(event);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mockRow(2L, alreadyCurrentKey, 2025, null, "2025-09-25",
                BigDecimal.ZERO, new BigDecimal("0.30"), null, null);
        stubSingleRow(jdbc, row);

        new DividendEventKeyMigration(jdbc).migrateEventKeys();

        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
    }

    @Test
    void migrationDoesNothingWhenTableIsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(new ArrayList<>());

        new DividendEventKeyMigration(jdbc).migrateEventKeys();

        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
    }

    @Test
    void migrationFailureIsSwallowedSoStartupDoesNotCrash() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db not ready"));

        // @PostConstruct 不得讓應用啟動整個炸掉；失敗只記錄警告，下次啟動重試。
        new DividendEventKeyMigration(jdbc).migrateEventKeys();
    }

    @SuppressWarnings("unchecked")
    private static void stubSingleRow(JdbcTemplate jdbc, ResultSet row) {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper rowMapper = invocation.getArgument(1);
            List result = new ArrayList<>();
            result.add(rowMapper.mapRow(row, 0));
            return result;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> captureBatchUpdate(JdbcTemplate jdbc) {
        org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(eq("UPDATE stock_dividend_snapshot_event SET event_key=? WHERE id=?"),
                captor.capture());
        return captor.getValue();
    }

    private static ResultSet mockRow(long id, String eventKey, Integer year,
            String exDividendDateIso, String exRightsDateIso,
            BigDecimal cashDividend, BigDecimal stockDividend,
            String cashPaymentDateIso, String stockPaymentDateIso) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("event_key")).thenReturn(eventKey);
        when(rs.getObject("year")).thenReturn(year);
        when(rs.getDate("ex_dividend_date")).thenReturn(toSqlDate(exDividendDateIso));
        when(rs.getDate("ex_rights_date")).thenReturn(toSqlDate(exRightsDateIso));
        when(rs.getBigDecimal("cash_dividend")).thenReturn(cashDividend);
        when(rs.getBigDecimal("stock_dividend")).thenReturn(stockDividend);
        when(rs.getDate("cash_payment_date")).thenReturn(toSqlDate(cashPaymentDateIso));
        when(rs.getDate("stock_payment_date")).thenReturn(toSqlDate(stockPaymentDateIso));
        return rs;
    }

    private static Date toSqlDate(String iso) {
        return iso == null ? null : Date.valueOf(iso);
    }
}
