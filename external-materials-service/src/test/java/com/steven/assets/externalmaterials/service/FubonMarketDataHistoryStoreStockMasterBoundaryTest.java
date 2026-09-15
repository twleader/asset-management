package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.service.FubonMarketData.StockBasicRead;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 435: Fubon basic-info names are source evidence, never a stock-master authority. */
class FubonMarketDataHistoryStoreStockMasterBoundaryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void persistBasicWritesOnlyTheFubonSourceFactWhenItsNameDisagreesWithTheMaster() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        FubonMarketDataHistoryStore store = new FubonMarketDataHistoryStore(jdbc, transactions);

        var result = store.persistBasic(new StockBasicRead("006208", LocalDate.of(2026, 9, 15),
                Instant.parse("2026-09-15T05:00:00Z"), "TWSE", "TSE",
                "FUBON ASSET MANAGEMENT CO LTD F", "ETF", "ETF", new BigDecimal("99.99"),
                new BigDecimal("90.01"), true, "NORMAL", 20, 1000, "TWD"));

        assertThat(result.status()).isEqualTo(FubonMarketDataHistoryStore.Status.WRITTEN);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("INSERT INTO fubon_stock_basic_info")
                .doesNotContain("INSERT INTO stock (").doesNotContain("UPDATE stock ");
    }
}
