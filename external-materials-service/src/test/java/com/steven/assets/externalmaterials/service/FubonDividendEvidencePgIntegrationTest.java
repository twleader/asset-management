package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.model.marketdata.DividendData.DividendEvent;
import com.steven.assets.externalmaterials.model.marketdata.DividendData.DividendFetchResult;
import com.steven.assets.externalmaterials.model.marketdata.DividendData.FetchStatus;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static com.steven.assets.externalmaterials.model.marketdata.FubonMarketData.*;
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
        String table = "stock_dividend_(?:fetch_attempt|fetch_observation|snapshot_event|snapshot|history)";
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
        String migration = Files.readString(Path.of(System.getProperty("user.dir")).getParent()
                .resolve("backend/src/main/resources/db/changelog/changes/v1.161.0-dividend-canonical-facts.sql"));
        jdbc.execute(migration); jdbc.execute(migration); // Exact production DDL is independently idempotent.
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
    @Test void canonicalReaderKeepsLegacyAndUnresolvedGapsAndNeverClaimsVerifiedPriceBasis() {
        var event=new DividendEvent(2026,new BigDecimal("6.00003573"),BigDecimal.ZERO,"2026-03-17",null,"2026-04-09",null,"primary");
        long snapshot=store.record("2330",MARKET,new DividendFetchResult("FinMind[TaiwanStockDividend]",List.of(event),FetchStatus.PARTIAL,
                DAY.minusYears(11),DAY,null,null,List.of(),2,"NOMINAL_PER_EX_DATE",true),NOW).snapshotId();
        jdbc.update("INSERT INTO stock_dividend_history(stock_code,market,year,cash_dividend,ex_dividend_date,event_key,resolution_status,canonical_date,source_snapshot_id) VALUES ('2330','台股',2026,6.00003573,'2026-03-17','resolved','RESOLVED','2026-03-17',?)",snapshot);
        jdbc.update("INSERT INTO stock_dividend_history(stock_code,market,year,cash_dividend,ex_dividend_date,event_key,resolution_status,canonical_date) VALUES ('2330','台股',2026,99,'2026-06-11','legacy','LEGACY',null),('2330','台股',2026,null,'2026-09-16','unknown','UNRESOLVED','2026-09-16'),('OTHER','台股',2026,999,'2026-03-17','other','LEGACY',null)");
        var events=new DividendHistoryQuery(jdbc).activeCashDividends("2330",MARKET);
        assertThat(events).hasSize(3);
        assertThat(events.getFirst().cashDividend()).isEqualByComparingTo("6.00003573");
        assertThat(events.subList(1,3)).allSatisfy(e->assertThat(e.cashDividend()).isNull());
        assertThat(events).allMatch(e->!e.priceBasisVerified());
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
    @Test void v2PreservesDistinctSameAmountComponentsAndLegacyHashesAcrossReplayAndRetiredStartup() {
        var legacyEvent = new DividendEvent(2026,new BigDecimal("6.000035"),BigDecimal.ZERO,"2026-03-17",null,"2026-04-09",null);
        var legacy = new DividendFetchResult("FinMind[TaiwanStockDividend]",List.of(legacyEvent),FetchStatus.PARTIAL,
                DAY.minusYears(11),DAY,null,"legacy",List.of());
        long legacySnapshot = store.record("2330",MARKET,legacy,NOW).snapshotId();
        String legacyKey = jdbc.queryForObject("SELECT event_key FROM stock_dividend_snapshot_event WHERE snapshot_id=?",String.class,legacySnapshot);
        assertThat(legacyKey).isEqualTo("7ce08554834ecd1b57d8427ad69e3fc702f0ae6998bb3d2d404f5979084af585");
        var ordinary = new DividendEvent(2026,new BigDecimal("6.00003573"),BigDecimal.ZERO,"2026-03-17",null,"2026-04-09",null,"ordinary");
        var special = new DividendEvent(2026,new BigDecimal("6.00003573"),BigDecimal.ZERO,"2026-03-17",null,"2026-04-09",null,"special");
        var fetched = new DividendFetchResult("FinMind[TaiwanStockDividend]",List.of(ordinary,special),FetchStatus.PARTIAL,
                DAY.minusYears(11),DAY,null,null,List.of(),2,"NOMINAL_PER_EX_DATE",true);
        long v2Snapshot = store.record("2330",MARKET,fetched,NOW.plusSeconds(1)).snapshotId();
        long replay = store.record("2330",MARKET,new DividendFetchResult(fetched.source(),List.of(special,ordinary),fetched.status(),
                fetched.scopeFrom(),fetched.scopeTo(),null,null,List.of(),2,"NOMINAL_PER_EX_DATE",true),NOW.plusSeconds(2)).snapshotId();
        assertThat(replay).isEqualTo(v2Snapshot).isNotEqualTo(legacySnapshot);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_snapshot_event WHERE snapshot_id=?",Integer.class,v2Snapshot)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT event_key) FROM stock_dividend_snapshot_event WHERE snapshot_id=?",Integer.class,v2Snapshot)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT cash_dividend FROM stock_dividend_snapshot_event WHERE snapshot_id=?",BigDecimal.class,v2Snapshot))
                .allSatisfy(value->assertThat(value).isEqualByComparingTo("6.00003573"));
        new DividendEventKeyMigration(jdbc).migrateEventKeys();
        assertThat(jdbc.queryForObject("SELECT event_key FROM stock_dividend_snapshot_event WHERE snapshot_id=?",String.class,legacySnapshot)).isEqualTo(legacyKey);
    }
    @Test void unidentifiedEqualRawRowsRemainTwoUnresolvedObservationsAndOutOfRangeCannotBeRoundedByPostgres() {
        var event = new DividendEvent(2026,new BigDecimal("0.123456789012345678"),BigDecimal.ZERO,"2026-03-17",null,null,null);
        var fetched = new DividendFetchResult("NASDAQ.stocks",List.of(event,event),FetchStatus.PARTIAL,
                DAY.minusYears(11),DAY,null,null,List.of(),2,"NOMINAL_PER_EX_DATE",false);
        long id = store.record("AAPL","美股",fetched,NOW).snapshotId();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_snapshot_event WHERE snapshot_id=?",Integer.class,id)).isEqualTo(2);
        var bad = new DividendEvent(2026,new BigDecimal("0.1234567890123456789"),BigDecimal.ZERO,"2026-03-17",null,null,null,"bad");
        assertThatThrownBy(()->store.record("AAPL","美股",new DividendFetchResult("NASDAQ.stocks",List.of(bad),FetchStatus.PARTIAL,
                DAY.minusYears(11),DAY,null,null,List.of(),2,"NOMINAL_PER_EX_DATE",true),NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_snapshot",Integer.class)).isEqualTo(1);
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
        assertThat((BigDecimal) official.get("cash_dividend")).isEqualByComparingTo(new BigDecimal("5"));
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
