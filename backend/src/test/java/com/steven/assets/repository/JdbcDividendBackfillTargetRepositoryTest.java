package com.steven.assets.repository;

import com.steven.assets.service.DividendBackfillTargetRepository.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 357.3a：回補對象清單的查詢語意。 */
class JdbcDividendBackfillTargetRepositoryTest {

    @Test
    @DisplayName("聯集 stock_dividend_history 與 stock 主檔，排除英股與台股 0000，排序穩定")
    void queriesTheUnionOfHistoryAndMasterWithDeterministicOrdering() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new JdbcDividendBackfillTargetRepository(jdbc).findBackfillTargets();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowCallbackHandler.class));
        String query = sql.getValue();
        assertThat(query).contains("FROM stock_dividend_history");
        assertThat(query).contains("UNION");
        assertThat(query).contains("FROM stock)");
        // 只跑台股與美股：英股配息不寫 stock_dividend_history，放進來只會製造必然失敗的紀錄。
        assertThat(query).contains("market IN ('台股', '美股')");
        assertThat(query).contains("NOT (code = '0000' AND market = '台股')");
        // 續跑與分批都依賴穩定順序。
        assertThat(query).contains("ORDER BY market, code");
    }

    @Test
    @DisplayName("每一列都成為一個 Target；多列走 void 區塊 callback，不得退化成 ResultSetExtractor")
    void mapsEveryRowToATarget() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("code")).thenReturn("2881", "AAPL");
        when(rs.getString("market")).thenReturn("台股", "美股");
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(rs);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        List<Target> targets = new JdbcDividendBackfillTargetRepository(jdbc).findBackfillTargets();

        assertThat(targets).containsExactly(new Target("2881", "台股"), new Target("AAPL", "美股"));
        assertThat(targets.getFirst().key()).isEqualTo("台股|2881");
    }
}
