package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CrawlerExportPathQuery#anyGdriveEnabled()} 的前置查詢契約（Requirement 52 / Task 247.4.4）。
 *
 * <p>重點只有一條：<b>這支查詢絕不能把啟動搞掛</b>。全新安裝的首次啟動時 {@code crawler_export_setting}
 * 可能還沒被 Liquibase 建出來（migration 由 backend 跑，兩個服務誰先起來沒有保證），此時 PostgreSQL 回的是
 * {@code BadSqlGrammarException}；本檔驗證它被吞成「視為無人啟用」，符合本類別 javadoc 既定的
 * 「表缺／DB 例外時 fallback」契約。
 */
class CrawlerExportPathQueryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CrawlerExportPathQuery query = new CrawlerExportPathQuery(jdbc);

    @Test
    void 表缺時視為無人啟用而非擲例外() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenThrow(
                new BadSqlGrammarException("selfcheck", "SELECT EXISTS (...)",
                        new SQLException("relation \"crawler_export_setting\" does not exist")));

        assertThat(query.anyGdriveEnabled()).isFalse();
    }

    @Test
    void 有列啟用時回true() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);

        assertThat(query.anyGdriveEnabled()).isTrue();
    }

    /** {@code queryForObject} 理論上可能回 null（驅動層行為），不得因此 NPE。 */
    @Test
    void 查詢回null時視為無人啟用() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(null);

        assertThat(query.anyGdriveEnabled()).isFalse();
    }
}
