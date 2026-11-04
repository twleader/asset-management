package com.steven.assets.repository;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import com.steven.assets.model.AnnualDividendCalculator;
import com.steven.assets.model.AnnualDividendData.*;

/** Exercises the actual owner predicates against PostgreSQL, including shared symbols and empty snapshots. */
@Testcontainers
class JdbcAnnualDividendReadRepositoryTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("annual_dividend_fixture").withUsername("fixture").withPassword("fixture-only");
    JdbcTemplate jdbc;
    JdbcAnnualDividendReadRepository repository;
    static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String table : new String[]{"asset_snapshot", "stock_holding", "asset_transaction", "stock_dividend_history", "market_type", "stock"})
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE asset_snapshot(id BIGINT PRIMARY KEY,owner_user_id BIGINT,snapshot_date DATE)");
        jdbc.execute("CREATE TABLE stock_holding(id BIGINT PRIMARY KEY,snapshot_id BIGINT,stock_code TEXT,market TEXT,shares NUMERIC(15,5))");
        jdbc.execute("CREATE TABLE asset_transaction(id BIGINT PRIMARY KEY,owner_user_id BIGINT,asset_code TEXT,market TEXT,asset_name TEXT,asset_type TEXT,trade_date DATE,transaction_type TEXT,shares NUMERIC(15,5))");
        jdbc.execute("CREATE TABLE stock_dividend_history(id BIGINT PRIMARY KEY,stock_code TEXT,market TEXT,year INTEGER,ex_dividend_date DATE,ex_rights_date DATE,cash_payment_date DATE,cash_dividend NUMERIC(15,6),stock_dividend NUMERIC(15,6),source TEXT,event_status TEXT)");
        jdbc.execute("CREATE TABLE market_type(code TEXT,display_name TEXT,sort_order INTEGER)");
        jdbc.execute("CREATE TABLE stock(code TEXT,market TEXT,name TEXT)");
        jdbc.update("INSERT INTO market_type VALUES('台股','台灣股市',1),('美股','美國股市',2)");
        jdbc.update("INSERT INTO stock VALUES('0050','台股','元大台灣50')");
        jdbc.update("INSERT INTO asset_snapshot VALUES(11,1,'2026-01-01'),(12,1,'2026-11-04'),(21,2,'2026-01-01'),(22,2,'2026-11-04'),(13,1,'2027-01-01')");
        jdbc.update("INSERT INTO stock_holding VALUES(1,11,'0050','台股',100),(2,21,'0050','台股',9999),(3,21,'OTHER','台股',900),(4,13,'FUTURE','台股',300)");
        jdbc.update("INSERT INTO asset_transaction VALUES(1,1,'0050','台股','owner1','股票','2026-02-01','買',20),(2,2,'0050','台股','owner2','股票','2026-02-01','買',500),(3,2,'PRIVATE','台股','secret','股票','2026-02-01','買',10),(4,1,'FUND','台股','fund','基金','2026-02-01','買',10),(5,1,'TOMORROW','台股','future','股票','2026-11-05','買',10),(6,1,'SOLD','台股','已賣出股票','股票','2026-02-01','賣',10)");
        jdbc.update("INSERT INTO stock_dividend_history VALUES(1,'0050','台股',2025,'2026-06-05',NULL,'2026-07-01',2,0,'official','ACTIVE'),(2,'0050','台股',2026,'2026-06-05',NULL,'2026-07-01',99,0,'old','CANCELLED'),(3,'OTHER','台股',2026,'2026-06-05',NULL,NULL,2,0,'official','ACTIVE'),(4,'PRIVATE','台股',2026,'2026-06-05',NULL,NULL,2,0,'official','ACTIVE'),(5,'SOLD','台股',2026,'2026-06-05',NULL,NULL,2,0,'official','ACTIVE')");
        repository = new JdbcAnnualDividendReadRepository(jdbc);
    }

    @Test void ownerScopeAppliesToUniverseSnapshotsPositionsLedgerNamesAndDividendJoin() {
        Facts one = repository.load(1, TODAY);
        assertThat(one.securities()).extracting(Security::code).containsExactly("0050", "SOLD");
        assertThat(one.securities()).filteredOn(s -> s.code().equals("SOLD")).extracting(Security::name).containsExactly("已賣出股票");
        assertThat(one.events()).extracting(Event::id).containsExactly(1L, 5L);
        assertThat(one.trades()).hasSize(2);
        assertThat(one.snapshots()).hasSize(2);
        assertThat(one.snapshots().getLast().positions()).isEmpty();
        var first = AnnualDividendCalculator.calculate(one, 2026, "", TODAY).rows().stream().filter(r -> r.code().equals("0050")).findFirst().orElseThrow();
        assertThat(first.shares()).isEqualByComparingTo("120");
        assertThat(first.estimatedCash()).isEqualByComparingTo("240.00");
        var two = repository.load(2, TODAY);
        assertThat(two.securities()).extracting(Security::code).containsExactly("0050", "OTHER", "PRIVATE");
        assertThat(two.trades()).hasSize(2);
        var second = AnnualDividendCalculator.calculate(two, 2026, "", TODAY).rows().stream().filter(r -> r.code().equals("0050")).findFirst().orElseThrow();
        assertThat(second.shares()).isEqualByComparingTo("10499");
        assertThat(repository.load(3, TODAY).securities()).isEmpty();
        assertThat(repository.load(3, TODAY).events()).isEmpty();
    }

    @Test void repositoryIsReadOnlyAndEmptyBaselineRemainsARealZeroSnapshot() {
        var before = jdbc.queryForList("SELECT * FROM stock_dividend_history ORDER BY id");
        Facts one = repository.load(1, TODAY);
        assertThat(one.snapshots().getLast().date()).isEqualTo(TODAY);
        assertThat(one.snapshots().getLast().positions()).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM stock_dividend_history ORDER BY id")).isEqualTo(before);
        assertThatThrownBy(() -> repository.load(-1, TODAY)).isInstanceOf(IllegalArgumentException.class);
    }
}
