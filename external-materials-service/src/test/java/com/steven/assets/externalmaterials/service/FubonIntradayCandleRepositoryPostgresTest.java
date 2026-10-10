package com.steven.assets.externalmaterials.service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Isolated PostgreSQL only: immutable conflicts, MVCC/asOf evidence and fixed-year boundaries. */
class FubonIntradayCandleRepositoryPostgresTest {
    static final Instant NOW=Instant.parse("2026-10-09T02:20:00Z");
    static final LocalDate DAY=LocalDate.of(2026,10,9);
    static PostgreSQLContainer<?> pg;
    static JdbcTemplate jdbc;
    FubonIntradayCandleRepository repository;
    @BeforeAll static void database() throws Exception {
        pg=new PostgreSQLContainer<>("postgres:16-alpine"); pg.start();
        var source=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
        jdbc=new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE fubon_intraday_candle (
                  stock_code varchar(20),market varchar(20),provider varchar(32),timeframe smallint,
                  candle_at timestamptz,source_date date,exchange varchar(20),open numeric(20,10),
                  high numeric(20,10),low numeric(20,10),close numeric(20,10),average numeric(20,10),
                  volume bigint,observed_at timestamptz,content_hash char(64),
                  PRIMARY KEY(stock_code,market,provider,timeframe,candle_at))
                """);
        jdbc.execute(Files.readString(Path.of("../backend/src/main/resources/db/changelog/changes/v1.148.0-fubon-intraday-candle-capture.sql")));
    }
    @AfterAll static void close() { if (pg!=null) pg.close(); }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE fubon_intraday_candle,fubon_intraday_candle_capture");
        repository=new FubonIntradayCandleRepository(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),
                new MarketClock(mock(MarketCalendar.class),Clock.fixed(NOW,ZoneOffset.UTC)));
    }
    @Test void onlyCompletedNonTailFactsCommitWithReceiptAndSameHashNeverRefreshesFirstObservation() {
        var read=read(NOW,rows("2026-10-09T02:00:00Z",21));
        var result=repository.capture(read,NOW.minusSeconds(8));
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.inserted()).isEqualTo(18);
        assertThat(jdbc.queryForObject("SELECT max(candle_at) FROM fubon_intraday_candle",Timestamp.class).toInstant())
                .isEqualTo(Instant.parse("2026-10-09T02:17:00Z"));
        var later=read(NOW.plusSeconds(5),read.candles());
        assertThat(repository.capture(later,NOW).unchanged()).isEqualTo(18);
        assertThat(jdbc.queryForObject("SELECT min(observed_at) FROM fubon_intraday_candle",Timestamp.class).toInstant()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT captured_at FROM fubon_intraday_candle_capture",Timestamp.class).toInstant())
                .isEqualTo(NOW.plusSeconds(5));
    }
    @Test void conflictRollsBackEarlierInsertsAndPublishesFailureInsteadOfOldAvailable() {
        repository.capture(read(NOW,rows("2026-10-09T02:00:00Z",21)),NOW.minusSeconds(8));
        var changed=new ArrayList<>(rows("2026-10-09T01:59:00Z",3));
        var old=changed.get(1);
        changed.set(1,new IntradayCandle(old.candleAt(),old.open(),old.high(),old.low(),new BigDecimal("10.5"),old.volume(),old.average()));
        var result=repository.capture(read(NOW.plusSeconds(10),changed),NOW.plusSeconds(5));
        assertThat(result.status()).isEqualTo("CONFLICT");
        assertThat(result.inserted()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fubon_intraday_candle WHERE candle_at='2026-10-09T01:59:00Z'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM fubon_intraday_candle_capture",String.class)).isEqualTo("CONFLICT");
        var snapshot=repository.read(List.of("2330"),DAY,NOW.plusSeconds(11),DAY.minusYears(1)).getFirst();
        assertThat(snapshot.status()).isEqualTo("CONFLICT"); assertThat(snapshot.candles()).isEmpty();
    }
    @Test void snapshotExcludesLateFirstObservedFactAndNeverReconstructsSupersededHistoricalReceipt() {
        repository.capture(read(NOW,rows("2026-10-09T02:00:00Z",21)),NOW.minusSeconds(8));
        jdbc.update("UPDATE fubon_intraday_candle SET observed_at=? WHERE candle_at=?",
                Timestamp.from(NOW.plusSeconds(10)),Timestamp.from(Instant.parse("2026-10-09T02:17:00Z")));
        var snapshot=repository.read(List.of("2330","2317"),DAY,NOW,DAY.minusYears(1));
        assertThat(snapshot.getFirst().candles()).hasSize(17);
        assertThat(snapshot.getFirst().lastCompletedAt()).isEqualTo(Instant.parse("2026-10-09T02:18:00Z"));
        assertThat(snapshot.get(1).status()).isNull();
        repository.unavailable("2330",DAY,NOW.plusSeconds(5),NOW.plusSeconds(10),"UNAVAILABLE","NO_DATA");
        var historical=repository.read(List.of("2330"),DAY,NOW,DAY.minusYears(1)).getFirst();
        assertThat(historical.status()).isNull(); assertThat(historical.candles()).isEmpty();
    }
    @Test void deleteUsesFixedCalendarYearInclusiveFloorAndBoundedBatches() {
        LocalDate floor=FubonMinuteRetentionFloor.at(NOW);
        jdbc.update("""
                INSERT INTO fubon_intraday_candle
                  (stock_code,market,provider,timeframe,candle_at,source_date,exchange,open,high,low,close,average,volume,observed_at,content_hash)
                SELECT '2330','台股','FUBON_SDK',1,'2025-10-08T01:00:00Z'::timestamptz+(n*INTERVAL '1 minute'),
                       ?, 'TWSE',10,11,9,10,999,1,?,repeat('a',64) FROM generate_series(0,10000) n
                """,floor.minusDays(1),Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO fubon_intraday_candle VALUES
                  ('2317','台股','FUBON_SDK',1,? ,?,'TWSE',10,11,9,10,999,1,?,repeat('b',64))
                """,Timestamp.from(floor.atTime(9,0).atZone(MarketClock.TW_ZONE).toInstant()),floor,Timestamp.from(NOW));
        assertThat(repository.deleteExpiredBatch(floor,false)).isEqualTo(10000);
        assertThat(repository.hasExpired(floor)).isTrue();
        assertThat(repository.deleteExpiredBatch(floor,false)).isEqualTo(1);
        assertThat(repository.hasExpired(floor)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fubon_intraday_candle",Integer.class)).isEqualTo(1);
        assertThat(FubonMinuteRetentionFloor.at(Instant.parse("2024-02-29T00:00:00Z"))).isEqualTo(LocalDate.of(2023,2,28));
        assertThat(FubonMinuteRetentionFloor.at(Instant.parse("2026-10-09T16:01:00Z"))).isEqualTo(LocalDate.of(2025,10,10));
    }
    @Test void oldMinuteWriterSkipsExpiredRowsButDoesNotAffectDailyHistory() {
        var store=new FubonMarketDataHistoryStore(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()));
        LocalDate expired=FubonMinuteRetentionFloor.today().minusDays(1);
        Instant at=expired.atTime(9,0).atZone(MarketClock.TW_ZONE).toInstant();
        var candle=rows(at.toString(),1).getFirst();
        var current=new IntradayCandlesRead("2330",expired,NOW,"TWSE","TSE",1,"AVAILABLE",null,List.of(candle));
        assertThat(store.persistCandles(current).written()).isZero();
        var historical=new HistoricalIntradayCandlesRead("2330",expired,expired,NOW,"TWSE","TSE","1","AVAILABLE",null,List.of(candle));
        assertThat(store.persistHistoricalCandles(historical).written()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fubon_intraday_candle",Integer.class)).isZero();
    }
    static IntradayCandlesRead read(Instant observed,List<IntradayCandle> candles) {
        return new IntradayCandlesRead("2330",DAY,observed,"TWSE","TSE",1,"AVAILABLE",null,candles);
    }
    static List<IntradayCandle> rows(String start,int count) {
        Instant first=Instant.parse(start); var result=new ArrayList<IntradayCandle>();
        for(int n=0;n<count;n++) result.add(new IntradayCandle(first.plusSeconds(n*60L),new BigDecimal("10"),
                new BigDecimal("11"),new BigDecimal("9"),new BigDecimal("10"),100L,new BigDecimal("999")));
        return result;
    }
}
