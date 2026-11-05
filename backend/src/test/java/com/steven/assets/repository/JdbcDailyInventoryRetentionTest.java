package com.steven.assets.repository;

import com.steven.assets.model.DailyInventory.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Old-schema fixtures are created before retention guards are installed; no production guard is disabled. */
@Testcontainers
class JdbcDailyInventoryRetentionTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("daily_retention_fixture").withUsername("fixture").withPassword("fixture-only");
    JdbcTemplate jdbc; TransactionTemplate tx; JdbcDailyInventoryHistoryRepository repository; Instant dbAt;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("DROP SCHEMA public CASCADE");jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY)");jdbc.execute("CREATE TABLE broker(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO app_user VALUES(1),(2)");
        migration("v1.162.0-daily-inventory-history.sql");
        repository=new JdbcDailyInventoryHistoryRepository(jdbc);
        dbAt=jdbc.queryForObject("SELECT statement_timestamp()",OffsetDateTime.class).toInstant();
    }
    void migration(String name) throws Exception {
        try(var in=getClass().getResourceAsStream("/db/changelog/changes/"+name)) {
            jdbc.execute(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    void retention() throws Exception {migration("v1.163.0-daily-inventory-retention.sql");}
    ZoneId zone(String market){return ZoneId.of("台股".equals(market)?"Asia/Taipei":"America/New_York");}
    LocalDate cutoff(String market){return dbAt.atZone(zone(market)).toLocalDate().minusMonths(66);}
    Capture capture(long owner,String market,LocalDate day,boolean empty) {
        var close=day.atTime("台股".equals(market)?LocalTime.of(13,30):LocalTime.of(16,0)).atZone(zone(market)).toInstant();
        var s=new Session(market,day,zone(market),close);
        var rows=empty?List.<Position>of():List.of(new Position(123,"ABC",null,new BigDecimal("1.12345"),BigDecimal.TEN,"USD",null));
        return new Capture(owner,s,s.scheduledAt(),50,day,rows);
    }
    void save(Capture c){tx.execute(status->repository.insert(c));}
    int purge(){return Objects.requireNonNull(tx.execute(status->repository.deleteExpired()));}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    LocalDate policy(String market,String at){return jdbc.queryForObject("SELECT daily_inventory_retention_cutoff(?,?::timestamptz)",LocalDate.class,market,at);}
    @Test void sixtySixCalendarMonthsUseMonthEndLeapYearAndEachMarketsLocalDate() throws Exception {
        retention();retention();
        assertThat(policy("台股","2026-08-31T04:00:00Z")).isEqualTo(LocalDate.of(2021,2,28));
        assertThat(policy("台股","2025-08-31T04:00:00Z")).isEqualTo(LocalDate.of(2020,2,29));
        assertThat(policy("台股","2026-11-06T02:00:00Z")).isEqualTo(LocalDate.of(2021,5,6));
        assertThat(policy("美股","2026-11-06T02:00:00Z")).isEqualTo(LocalDate.of(2021,5,5));
        assertThatThrownBy(()->policy("UNKNOWN","2026-11-06T02:00:00Z")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->policy("台股",null)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void purgeRemovesExpiredChildrenAndEmptyHeadersOnlyAndIncludesBoundary() throws Exception {
        save(capture(1,"台股",cutoff("台股").minusDays(1),false));
        save(capture(2,"美股",cutoff("美股").minusDays(1),true));
        save(capture(1,"台股",cutoff("台股"),false));
        save(capture(2,"美股",cutoff("美股"),false));
        jdbc.execute("CREATE TABLE untouched_fixture(id INTEGER)");jdbc.update("INSERT INTO untouched_fixture VALUES(1)");
        retention();assertThat(purge()).isEqualTo(2);assertThat(purge()).isZero();
        assertThat(count("daily_inventory_snapshot")).isEqualTo(2);assertThat(count("daily_inventory_position")).isEqualTo(2);
        assertThat(count("untouched_fixture")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT trading_date FROM daily_inventory_snapshot ORDER BY market",LocalDate.class))
                .containsExactly(cutoff("台股"),cutoff("美股"));
    }
    @Test void finalParentFailureRollsBackAlreadyDeletedPositionsThenRetrySucceeds() throws Exception {
        save(capture(1,"台股",cutoff("台股").minusDays(1),false));retention();
        // An independent FK simulates a failure after the child DELETE; production guards remain enabled.
        jdbc.execute("CREATE TABLE retention_failure_fixture(header_id BIGINT REFERENCES daily_inventory_snapshot(id))");
        jdbc.update("INSERT INTO retention_failure_fixture SELECT id FROM daily_inventory_snapshot");
        assertThatThrownBy(this::purge).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("daily_inventory_snapshot")).isEqualTo(1);assertThat(count("daily_inventory_position")).isEqualTo(1);
        jdbc.execute("DROP TABLE retention_failure_fixture");assertThat(purge()).isEqualTo(1);
    }
    @Test void guardsRejectLiveDeleteAllUpdatesAndExpiredHeaderChildReplayWithoutCallerDateOverride() throws Exception {
        save(capture(1,"台股",cutoff("台股").minusDays(1),false));
        save(capture(1,"美股",cutoff("美股"),false));retention();
        for(String sql:List.of("DELETE FROM daily_inventory_snapshot WHERE market='美股'",
                "DELETE FROM daily_inventory_position WHERE snapshot_id IN (SELECT id FROM daily_inventory_snapshot WHERE market='美股')",
                "UPDATE daily_inventory_snapshot SET status='EMPTY'", "UPDATE daily_inventory_position SET shares=0"))
            assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->save(capture(2,"台股",cutoff("台股").minusDays(2),false)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("expired");
        assertThatThrownBy(()->jdbc.update("INSERT INTO daily_inventory_position(snapshot_id,source_holding_id,stock_code,shares,investment_cost) SELECT id,456,'LATE',1,1 FROM daily_inventory_snapshot WHERE market='台股'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("expired");
        // Caller/session values do not participate in guard time. Changing the session timezone also cannot relax it.
        assertThatThrownBy(()->tx.execute(status->{
            jdbc.execute("SET LOCAL TIME ZONE 'Pacific/Kiritimati'");
            jdbc.execute("SET LOCAL app.retention_today='2099-12-31'");
            return jdbc.update("DELETE FROM daily_inventory_snapshot WHERE market='美股'");
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(purge()).isEqualTo(1);assertThat(count("daily_inventory_snapshot")).isEqualTo(1);
    }
}
