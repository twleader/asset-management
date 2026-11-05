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
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Runs the actual migration twice and exercises PostgreSQL constraints/triggers and transaction rollback. */
@Testcontainers
class JdbcDailyInventoryHistoryRepositoryTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("daily_inventory_fixture").withUsername("fixture").withPassword("fixture-only");
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    JdbcDailyInventoryHistoryRepository repository;
    static final Session TW=new Session("台股",LocalDate.of(2026,11,6),ZoneId.of("Asia/Taipei"),Instant.parse("2026-11-06T05:30:00Z"));
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("DROP SCHEMA public CASCADE");jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE broker(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO app_user VALUES(1),(2)");
        String migration;
        try(var in=getClass().getResourceAsStream("/db/changelog/changes/v1.162.0-daily-inventory-history.sql")) {
            migration=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        }
        jdbc.execute(migration);jdbc.execute(migration);
        repository=new JdbcDailyInventoryHistoryRepository(jdbc);
    }
    Position position(long id,String shares) {
        return new Position(id,"AAPL",null,new BigDecimal(shares),new BigDecimal("123.45"),"USD",new BigDecimal("32.1234"));
    }
    Capture capture(long owner,List<Position> rows) {
        return new Capture(owner,TW,Instant.parse("2026-11-06T11:30:01Z"),50,LocalDate.of(2026,11,6),rows);
    }
    boolean save(Capture c){return Boolean.TRUE.equals(tx.execute(status->repository.insert(c)));}
    @Test void idempotenceOwnerSeparationEmptyAndRawFieldsArePreserved() {
        assertThat(save(capture(1,List.of(position(1,"0.12345"))))).isTrue();
        assertThat(save(capture(1,List.of(position(2,"999"))))).isFalse();
        assertThat(save(capture(2,List.of()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_inventory_snapshot",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_inventory_position",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM daily_inventory_snapshot WHERE owner_user_id=2",String.class)).isEqualTo("EMPTY");
        var row=jdbc.queryForMap("SELECT shares,investment_cost,currency,transaction_exchange_rate FROM daily_inventory_position");
        assertThat((BigDecimal)row.get("shares")).isEqualByComparingTo("0.12345");
        assertThat((BigDecimal)row.get("investment_cost")).isEqualByComparingTo("123.45");
        assertThat(row.get("currency")).isEqualTo("USD");
        assertThat((BigDecimal)row.get("transaction_exchange_rate")).isEqualByComparingTo("32.1234");
    }
    @Test void failureOnSecondPositionRollsBackHeaderAndFirstPositionAndRetryCanSucceed() {
        assertThatThrownBy(()->save(capture(1,List.of(position(1,"1"),position(2,"-1")))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_inventory_snapshot",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_inventory_position",Integer.class)).isZero();
        assertThat(save(capture(1,List.of(position(1,"1"))))).isTrue();
    }
    @Test void immutableTriggersRejectUpdateDeleteAndSourceIdsNeedNoLiveSourceRows() {
        save(capture(1,List.of(position(987,"1"))));
        for(String sql:List.of("UPDATE daily_inventory_snapshot SET status='EMPTY'","DELETE FROM daily_inventory_snapshot",
                "UPDATE daily_inventory_position SET shares=2","DELETE FROM daily_inventory_position"))
            assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("immutable");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE tgname IN ('daily_inventory_snapshot_immutable','daily_inventory_position_immutable')",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='daily_inventory_snapshot' AND data_type='timestamp with time zone'",Integer.class)).isEqualTo(3);
    }
    @Test void concurrentInstancesCommitExactlyOneCompleteCapture() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<Boolean> work=()->{ready.countDown();start.await();return save(capture(1,List.of(position(1,"1"),position(2,"2"))));};
            var one=executor.submit(work);var two=executor.submit(work);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_inventory_snapshot",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_inventory_position",Integer.class)).isEqualTo(2);
    }
}
