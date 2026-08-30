package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.FubonEtfHoldingsSnapshot;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.*;
import com.steven.assets.service.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FubonEtfHoldingsWriter.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FubonEtfHoldingsPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("etf_holdings_test").withUsername("assets").withPassword("test-only-password");
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired FubonEtfHoldingsWriter writer;
    @Autowired FubonEtfHoldingsSnapshotRepository repository;
    @Autowired StockHoldingRepository stocks;
    @Autowired AssetSnapshotRepository snapshots;
    @Autowired StockAlertRepository alerts;
    @Autowired JdbcTemplate jdbc;
    private static final Instant NOW = Instant.parse("2026-08-28T07:30:00Z");
    private static final String PAYLOAD = """
            {"schemaVersion":1,"stockCode":"0050","sourceDate":"2026-08-27","holdings":[
              {"stockCode":"2330","stockName":"台積電","weight":"58.82","shares":"530358242"}]}
            """;

    @BeforeEach
    void useActualEtfSchemaInsteadOfHibernateTypeInference() throws Exception {
        // db/schema.sql is authoritative, including timestamp-without-time-zone and JSONB types.
        String schema = Files.readString(Path.of("..", "db", "schema.sql"));
        int start = schema.indexOf("CREATE TABLE public.fubon_etf_holdings_snapshot (");
        int end = schema.indexOf("\n);", start) + 3;
        assertThat(start).isGreaterThanOrEqualTo(0);
        jdbc.execute("DROP TABLE fubon_etf_holdings_snapshot");
        jdbc.execute(schema.substring(start, end));
        jdbc.execute("ALTER TABLE fubon_etf_holdings_snapshot ADD PRIMARY KEY (etf_stock_code)");
        stocks.deleteAll(); alerts.deleteAll(); snapshots.deleteAll();
    }

    @Test
    void jsonbStoresObjectAndReadApiUsesProviderDateAfterRealDatabaseRoundtrip() {
        writer.save(row("0050", true, null, PAYLOAD));
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(raw_response_json) FROM fubon_etf_holdings_snapshot WHERE etf_stock_code='0050'", String.class)).isEqualTo("object");
        assertThat(jdbc.queryForObject("SELECT raw_response_json->>'sourceDate' FROM fubon_etf_holdings_snapshot WHERE etf_stock_code='0050'", String.class)).isEqualTo("2026-08-27");
        var persisted = repository.findById("0050").orElseThrow();
        assertThat(persisted.getFetchedAt()).isEqualTo(NOW);
        var result = new MarketDataService("http://127.0.0.1:1", null, repository).getEtfHoldings("0050", "台股");
        assertThat(result.asOfDate()).isEqualTo("2026-08-27");
        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().getFirst().shares()).isEqualByComparingTo("530358242");
    }

    @Test
    void failedBatchClearsPreviouslySuccessfulPayloadInCommittedDatabase() {
        writer.save(row("0050", true, null, PAYLOAD));
        var config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "test", null));
        var market = mock(MarketDataService.class);
        when(market.isTwTradingDayKnown(LocalDate.of(2026, 8, 28))).thenReturn(Optional.of(true));
        when(market.isEtf("0050", "台股")).thenReturn(true);
        var radar = mock(StockHoldingRepository.class);
        when(radar.findTwRadarCandidateCodes()).thenReturn(List.of("0050"));
        var broker = mock(FubonBrokerClient.class);
        when(broker.readEtfHoldings(List.of("0050"))).thenReturn(FubonDtos.CallResult.failure("ADAPTER_5XX"));
        new FubonEtfHoldingsSyncService(true, config, market, broker, writer, radar, Clock.fixed(NOW, ZoneOffset.UTC)).syncScheduled();
        var persisted = repository.findById("0050").orElseThrow();
        assertThat(persisted.getSuccess()).isFalse();
        assertThat(persisted.getReason()).isEqualTo("ADAPTER_5XX");
        assertThat(persisted.getRawResponseJson()).isNull();
    }

    @Test
    void oneRealDatabaseConstraintFailureDoesNotRollbackOtherEtfs() {
        writer.save(row("0050", true, null, PAYLOAD));
        assertThatThrownBy(() -> writer.save(row("0056", false, "x".repeat(51), null))).isInstanceOf(RuntimeException.class);
        writer.save(row("006208", false, "ETF_HOLDINGS_TIMEOUT", null));
        assertThat(repository.findById("0050")).isPresent();
        assertThat(repository.findById("0056")).isEmpty();
        assertThat(repository.findById("006208").orElseThrow().getReason()).isEqualTo("ETF_HOLDINGS_TIMEOUT");
    }

    @Test
    void radarSqlSelectsEachOwnersLatestSnapshotAndTaiwanAlertsOnly() {
        holding(11L, "2026-08-26", "0050", "台股");
        holding(11L, "2026-08-28", "0056", "台股");
        holding(12L, "2026-08-27", "006208", "台股");
        holding(13L, "2026-08-28", "QQQ", "美股");
        alerts.saveAndFlush(StockAlert.builder().ownerUserId(14L).stockCode("00981A").market("台股")
                .alertType("PRICE_ABOVE").threshold(BigDecimal.ONE).build());
        alerts.saveAndFlush(StockAlert.builder().ownerUserId(14L).stockCode("SPY").market("美股")
                .alertType("PRICE_ABOVE").threshold(BigDecimal.ONE).build());
        alerts.saveAndFlush(StockAlert.builder().ownerUserId(11L).stockCode("0056").market("台股")
                .alertType("PRICE_ABOVE").threshold(BigDecimal.ONE).build());
        assertThat(stocks.findTwRadarCandidateCodes()).containsExactly("0056", "006208", "00981A");
    }

    private void holding(Long owner, String date, String code, String market) {
        var snapshot = snapshots.saveAndFlush(AssetSnapshot.builder().ownerUserId(owner).snapshotDate(LocalDate.parse(date)).build());
        stocks.saveAndFlush(StockHolding.builder().snapshot(snapshot).stockCode(code).market(market)
                .shares(BigDecimal.ONE).investmentCost(BigDecimal.ONE).currentValue(BigDecimal.ONE).build());
    }

    private FubonEtfHoldingsSnapshot row(String code, boolean success, String reason, String json) {
        return FubonEtfHoldingsSnapshot.builder().etfStockCode(code).market("台股").success(success)
                .reason(reason).rawResponseJson(json).fetchedAt(NOW).updatedAt(NOW).build();
    }
}
