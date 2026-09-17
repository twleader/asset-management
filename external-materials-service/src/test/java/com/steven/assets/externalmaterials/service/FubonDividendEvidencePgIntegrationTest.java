package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient.DividendEvent;
import com.steven.assets.externalmaterials.client.DividendFetchClient.DividendFetchResult;
import com.steven.assets.externalmaterials.client.DividendFetchClient.FetchStatus;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = false)
class FubonDividendEvidencePgIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static DividendSnapshotStore store;
    static final LocalDate DAY = LocalDate.of(2026, 8, 28);
    static final Instant NOW = Instant.parse("2026-08-28T05:30:01Z");
    @BeforeAll static void createCanonicalSchema() throws Exception {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        // Execute current canonical table/sequence/default/constraint definitions, not guessed fixture columns.
        String schema = Files.readString(Path.of(System.getProperty("user.dir")).getParent().resolve("db/schema.sql"));
        String table = "stock_dividend_(?:fetch_attempt|fetch_observation|snapshot_event|snapshot)";
        for (String regex : List.of("CREATE TABLE public\\." + table + " \\(.*?\\);",
                "CREATE SEQUENCE public\\." + table + "_id_seq\\s.*?;",
                "ALTER TABLE ONLY public\\." + table + "(?: ALTER COLUMN|\\s+ADD CONSTRAINT).*?;")) {
            var matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(schema);
            int count = 0;
            while (matcher.find()) { jdbc.execute(matcher.group()); count++; }
            assertThat(count).isPositive();
        }
        // Stock identity is required by the production scope query; preserve the canonical master shape.
        for (String regex : List.of("CREATE TABLE public\\.stock \\(.*?\\);",
                "ALTER TABLE ONLY public\\.stock\\s+ADD CONSTRAINT.*?;")) {
            var matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(schema);
            int count = 0;
            while (matcher.find()) { jdbc.execute(matcher.group()); count++; }
            assertThat(count).isPositive();
        }
        jdbc.execute("CREATE TABLE asset_snapshot (id bigint, owner_user_id bigint, snapshot_date date)");
        jdbc.execute("CREATE TABLE stock_holding (stock_code varchar(20), market varchar(20), snapshot_id bigint)");
        jdbc.execute("CREATE TABLE stock_alert (stock_code varchar(20), market varchar(20))");
        TransactionProxyFactoryBean proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new DividendSnapshotStore(jdbc)); proxy.setProxyTargetClass(true);
        proxy.setTransactionManager(new DataSourceTransactionManager(ds));
        Properties attributes = new Properties(); attributes.setProperty("record", "PROPAGATION_REQUIRED");
        proxy.setTransactionAttributes(attributes); proxy.afterPropertiesSet();
        store = (DividendSnapshotStore)proxy.getObject();
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE stock_dividend_fetch_attempt,stock_dividend_fetch_observation,stock_dividend_snapshot_event,stock_dividend_snapshot RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE asset_snapshot,stock_holding,stock_alert,stock");
        jdbc.update("INSERT INTO stock (code,market,name) VALUES ('0050','台股','元大台灣50'),('2330','台股','台積電')");
        jdbc.update("INSERT INTO stock_alert VALUES ('2330','台股'),('0050','台股'),('0000','台股')");
    }
    private FubonDividendEvidenceSyncService service(FubonMarketDataPort client) {
        var access = mock(FubonMarketAccess.class);
        var clock = mock(MarketClock.class); when(clock.instant()).thenReturn(NOW);
        var calendar = mock(MarketCalendar.class); when(calendar.isTwTradingDayKnown(DAY)).thenReturn(Optional.of(true));
        return new FubonDividendEvidenceSyncService("true", new FubonMarketRunGate(access, calendar, clock),
                new FubonRadarScope(new StockSourceQuery(jdbc)), client, store, clock);
    }
    private static DividendEvent cash(String amount) {
        return new DividendEvent(2026, new BigDecimal(amount), null, "2026-09-15", "2026-09-15", null, null);
    }
    private static DividendBatch batch(Instant observed) {
        return new DividendBatch(DAY, observed, DAY.minusDays(320), DAY.plusDays(45), List.of(
                new DividendRow("0050", "PARTIAL", false, "NO_MATCHING_EVENTS", List.of()),
                new DividendRow("2330", "PARTIAL", true, "STOCK_DIVIDEND_UNIT_UNVERIFIED", List.of(cash("4.500000")))));
    }
    @Test void twoRealRunsReuseSnapshotAndEventAppendObservationsWithCorrectScopeAndNullAvailability() {
        var client = mock(FubonMarketDataPort.class);
        when(client.dividends(List.of("0050", "2330"), DAY)).thenReturn(batch(NOW), batch(NOW.plusSeconds(1)));
        var service = service(client);
        assertThat(service.sync(false).persistedCount()).isEqualTo(2);
        assertThat(service.sync(false).outcome()).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_snapshot", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_snapshot_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_fetch_observation", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT status FROM stock_dividend_fetch_observation", String.class)).containsOnly("PARTIAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_fetch_observation WHERE complete OR source_available_at IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT ex_rights_date::text FROM stock_dividend_snapshot_event", String.class)).isEqualTo("2026-09-15");
        assertThat(jdbc.queryForObject("SELECT min(scope_from)::text FROM stock_dividend_snapshot", String.class)).isEqualTo("2025-10-12");
        verify(client, times(2)).dividends(List.of("0050", "2330"), DAY);
    }
    @Test void dryRunNeverAppendsAndPartialOrFailedEvidenceCannotOverwriteExistingOfficialFutureSnapshot() {
        store.record("2330", MARKET, new DividendFetchResult("TWSE", List.of(cash("5")), FetchStatus.COMPLETE,
                DAY.minusDays(320), DAY.plusDays(45), NOW, null, List.of()), NOW);
        var client = mock(FubonMarketDataPort.class);
        when(client.dividends(anyList(), eq(DAY))).thenReturn(batch(NOW));
        var service = service(client);
        assertThat(service.sync(true).outcome()).isEqualTo("DRY_RUN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_snapshot", Integer.class)).isEqualTo(1);
        service.sync(false);
        when(client.dividends(anyList(), eq(DAY))).thenThrow(new Unavailable("UPSTREAM_UNAVAILABLE"));
        assertThat(service.sync(false).outcome()).isEqualTo("DIVIDEND_FAILED");
        var official = jdbc.queryForMap("""
                SELECT e.cash_dividend,o.complete,o.status,e.ex_dividend_date
                FROM stock_dividend_snapshot s JOIN stock_dividend_snapshot_event e ON e.snapshot_id=s.id
                JOIN stock_dividend_fetch_observation o ON o.snapshot_id=s.id WHERE s.provider='TWSE'
                """);
        assertThat(official.get("cash_dividend")).isEqualTo(new BigDecimal("5.000000"));
        assertThat(official.get("complete")).isEqualTo(true);
        assertThat(official.get("status")).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_dividend_fetch_observation o JOIN stock_dividend_snapshot s ON s.id=o.snapshot_id
                WHERE s.provider='FUBON_SDK' AND o.complete
                """, Integer.class)).isZero();
    }
    @Test void oneFailedDatabaseTransactionRollsBackOnlyThatSymbolAndValidPeerCommits() {
        var client = mock(FubonMarketDataPort.class);
        when(client.dividends(anyList(), eq(DAY))).thenReturn(new DividendBatch(DAY, NOW, DAY.minusDays(320),
                DAY.plusDays(45), List.of(
                new DividendRow("0050", "PARTIAL", true, null, List.of(cash("999999999999999999999999"))),
                new DividendRow("2330", "PARTIAL", true, "STOCK_DIVIDEND_UNIT_UNVERIFIED", List.of(cash("4.5"))))));
        var result = service(client).sync(false);
        assertThat(result.persistedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT stock_code FROM stock_dividend_snapshot", String.class)).containsExactly("2330");
    }
}
