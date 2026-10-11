package com.steven.assets.config;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.steven.assets.config.LiquibaseHistoricalFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual SpringLiquibase startup against isolated PostgreSQL, with immutable historical metadata. */
@Testcontainers
class LiquibaseHistoryPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("liquibase_history_test").withUsername("assets").withPassword("test-only");

    @TempDir Path temporaryResources;
    DriverManagerDataSource datasource;
    JdbcTemplate jdbc;

    @BeforeEach
    void resetIsolatedSchema() {
        datasource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        jdbc.execute("""
                CREATE TABLE databasechangelog (
                  id varchar(255) NOT NULL, author varchar(255) NOT NULL, filename varchar(255) NOT NULL,
                  dateexecuted timestamp NOT NULL, orderexecuted integer NOT NULL, exectype varchar(10) NOT NULL,
                  md5sum varchar(35), description varchar(255), comments varchar(255), tag varchar(255),
                  liquibase varchar(20), contexts varchar(255), labels varchar(255), deployment_id varchar(10))
                """);
        jdbc.execute("CREATE TABLE trading_calendar_export_schedule (id bigint PRIMARY KEY)");
    }

    @Test
    void actualMasterStartupAddsNoChangesetsAndPreservesAllHistoricalMetadata() throws Exception {
        seed(compatibleHistory(parse(MASTER, getClass().getClassLoader())));
        var before = history();

        startup(MASTER, getClass().getClassLoader());

        assertThat(history()).containsExactlyElementsOf(before);
        assertThat(before).hasSizeGreaterThanOrEqualTo(194);
        assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",
                String.class)).containsExactly("databasechangelog", "databasechangeloglock",
                "trading_calendar_export_schedule");
    }

    @Test
    void removingIncludeLogicalPathReproducesExistingCalendarTableFailure() throws Exception {
        seed(compatibleHistory(parse(MASTER, getClass().getClassLoader())));
        var before = history();
        String master = masterContent();
        assertThat(master).containsOnlyOnce(CALENDAR_INCLUDE_ATTRIBUTE);
        Files.writeString(temporaryResources.resolve("negative-master.yaml"),
                master.replace(CALENDAR_INCLUDE_ATTRIBUTE, ""), StandardCharsets.UTF_8);

        try (var loader = temporaryClassLoader()) {
            assertThatThrownBy(() -> startup("negative-master.yaml", loader))
                    .hasStackTraceContaining("v1.56.0-trading-calendar-export-schedule.sql::v1.55.0-trading-calendar-export-schedule::steven")
                    .hasStackTraceContaining("relation \"trading_calendar_export_schedule\" already exists");
        }
        assertThat(history()).containsExactlyElementsOf(before);
    }

    @Test
    void laterLegitimateAppendCanBeSeededWithoutReplacingFrozenHistoricalIdentities() throws Exception {
        Files.writeString(temporaryResources.resolve("future-append.sql"), """
                --liquibase formatted sql
                --changeset test-only:task491-future-append
                CREATE TABLE task491_future_should_not_run (id integer PRIMARY KEY);
                """, StandardCharsets.UTF_8);
        Files.writeString(temporaryResources.resolve("appended-master.yaml"), masterContent() + """

                  - include:
                      file: future-append.sql
                      relativeToChangelogFile: false
                """, StandardCharsets.UTF_8);

        try (var loader = temporaryClassLoader()) {
            var current = parse("appended-master.yaml", loader);
            var normal = compatibleHistory(parse(MASTER, getClass().getClassLoader()));
            var augmented = compatibleHistory(current);
            assertThat(augmented).containsAll(FROZEN.all()).hasSize(normal.size() + 1);
            assertThat(augmented.getLast().id()).isEqualTo("task491-future-append");
            seed(augmented);
            var before = history();

            startup("appended-master.yaml", loader);

            assertThat(history()).containsExactlyElementsOf(before);
            assertThat(jdbc.queryForObject("SELECT to_regclass('public.task491_future_should_not_run') IS NULL",
                    Boolean.class)).isTrue();
        }
    }

    private void seed(List<Migration> migrations) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < migrations.size(); i++) {
            var row = migrations.get(i);
            rows.add(new Object[]{row.id(), row.author(), row.filename(),
                    Timestamp.valueOf("2000-01-01 00:00:00"), i + 1, row.checksum()});
        }
        jdbc.batchUpdate("""
                INSERT INTO databasechangelog (id,author,filename,dateexecuted,orderexecuted,exectype,md5sum,liquibase)
                VALUES (?,?,?,?,?,'EXECUTED',?,'test-fixture')
                """, rows);
    }

    private List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT * FROM databasechangelog ORDER BY orderexecuted");
    }

    private void startup(String root, ClassLoader loader) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(datasource);
        liquibase.setChangeLog("classpath:" + root);
        liquibase.setResourceLoader(new DefaultResourceLoader(loader));
        liquibase.afterPropertiesSet();
    }

    private String masterContent() throws Exception {
        return new ClassPathResource(MASTER).getContentAsString(StandardCharsets.UTF_8);
    }

    private URLClassLoader temporaryClassLoader() throws Exception {
        return new URLClassLoader(new URL[]{temporaryResources.toUri().toURL()}, getClass().getClassLoader());
    }
}
