package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.repository.*;
import com.steven.assets.repository.SrppReportRepository.Scope;
import com.steven.assets.service.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.srpp.SrppJcs;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Production transaction advice, the real missing-target path and publication run against an isolated database. */
@Testcontainers
class SrppOptionalRadarTransactionPostgresTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("task516_optional_radar").withUsername("assets").withPassword("test-only");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private SrppReportRepository repository;
    private SrppReportPublisher publisher;
    private Scope scope;
    private final List<TxFrame> innerTransactions = new ArrayList<>();
    private final List<RuntimeException> missingTargets = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; "
                + "CREATE TABLE api_error_log_operation(source varchar(16),operation_key varchar(96),operation_label varchar(96),display_order int,PRIMARY KEY(source,operation_key)); "
                + "CREATE TABLE app_user(id bigint PRIMARY KEY); INSERT INTO app_user VALUES(7); "
                + "CREATE TABLE srpp_policy_registry(policy_bundle_sha256 char(64) PRIMARY KEY); "
                + "CREATE FUNCTION reject_srpp_immutable_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable'; END $$;");
        jdbc.update("INSERT INTO srpp_policy_registry VALUES(?)", AUTHORITY.decision().policy().bundleHash());
        jdbc.execute(new ClassPathResource("db/changelog/changes/v1.150.0-srpp-report-calculations.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        repository = new SrppReportRepository(jdbc);
        publisher = new SrppReportPublisher(repository, AUTHORITY, new PureSrppReportCalculations(), MAPPER,
                manager, new SrppReportBuildIdentity());
        scope = new Scope(7, DATE, "09:05", supported().bundleHash(), SWAGGER, SrppReportModuleCalculator.VERSION);
    }

    @Test
    void productionAdviceIsolatesMissingRadarAndCommitsBothConsumersWithUnavailableWireEvidence() {
        var radar = radarProxy(false);
        var published = new TransactionTemplate(manager).execute(outer -> {
            repository.lockOwnerDate(7, DATE);
            TxFrame before = transactionFrame();
            Frozen completed = captureMissingRadar(radar);
            assertThat(outer.isRollbackOnly()).isFalse();
            assertThat(transactionFrame()).isEqualTo(before);
            assertThat(innerTransactions).singleElement().satisfies(inner -> {
                assertThat(inner.transactionId()).isNotEqualTo(before.transactionId());
                assertThat(inner.backendPid()).isNotEqualTo(before.backendPid());
                assertThat(inner.readOnly()).isEqualTo("on");
            });
            assertOwnerDateLockHeld();
            return publisher.publish(scope, completed, supported(), completed.input().path("sources"),
                    states(7, "09:05"), Instant.now());
        });

        assertThat(published).hasSize(2);
        assertThat(published.getFirst().sharedId()).isEqualTo(published.getLast().sharedId());
        assertThat(published.getFirst().id()).isNotEqualTo(published.getLast().id());
        assertThat(published).extracting(p -> p.consumer()).containsExactly("Codex", "Claude");
        assertThat(missingTargets).singleElement().satisfies(missing -> assertThat(missing).hasMessage("找不到可分析標的"));
        assertThat(count("srpp_report_calculation_base")).isEqualTo(1);
        assertThat(count("srpp_report_calculation_package")).isEqualTo(2);
        for (var saved : published) {
            var readback = repository.byId(7, saved.id()).orElseThrow();
            assertThat(readback.content()).isEqualTo(saved.content());
            var wire = SrppReportEnvelopeValidator.validate(readback.content());
            assertThat(wire.path("identity").path("consumer").asText()).isEqualTo(saved.consumer());
            var input = SrppJcs.parseStrict(wire.path("inputSnapshot").path("inputBundleJcs").asText());
            assertThat(input.path("sources").path("market:台股:00850").path("radar").isNull()).isTrue();
            assertThat(wire.path("inputSnapshot").path("sourceVector").path("market:台股:00850")
                    .path("validation").asText()).isEqualTo("UNAVAILABLE");
            assertThat(wire.path("tradeAuthorization").asBoolean()).isFalse();
            assertThat(wire.path("placesOrders").asBoolean()).isFalse();
        }
    }

    @Test
    void sameRealMissingTargetWithLegacyRequiredAdvicePoisonsPublicationAndPersistsNothing() {
        var radar = radarProxy(true);
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(outer -> {
            repository.lockOwnerDate(7, DATE);
            TxFrame before = transactionFrame();
            Frozen completed = captureMissingRadar(radar);
            assertThat(outer.isRollbackOnly()).isTrue();
            assertThat(innerTransactions).containsExactly(before);
            publisher.publish(scope, completed, supported(), completed.input().path("sources"),
                    states(7, "09:05"), Instant.now());
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(missingTargets).singleElement().satisfies(missing -> assertThat(missing).hasMessage("找不到可分析標的"));
        assertThat(count("srpp_report_calculation_base")).isZero();
        assertThat(count("srpp_report_calculation_package")).isZero();
    }

    @Test
    void unrelatedOuterFailureStillRollsBackBaseAndBothConsumersAfterIsolatedMissingRadar() {
        var radar = radarProxy(false);
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(outer -> {
            repository.lockOwnerDate(7, DATE);
            Frozen completed = captureMissingRadar(radar);
            var published = publisher.publish(scope, completed, supported(), completed.input().path("sources"),
                    states(7, "09:05"), Instant.now());
            assertThat(published).hasSize(2);
            assertThat(count("srpp_report_calculation_package")).isEqualTo(2);
            throw new IllegalStateException("global publication failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("global publication failure");
        assertThat(count("srpp_report_calculation_base")).isZero();
        assertThat(count("srpp_report_calculation_package")).isZero();
    }

    private TradingRadarService radarProxy(boolean legacyRequired) {
        // Only persistence dependencies are mocked: the actual production method and its private
        // target selection throw the same missing-target exception as the failing background run.
        var target = mock(TradingRadarService.class, CALLS_REAL_METHODS);
        var snapshots = mock(AssetSnapshotRepository.class);
        var alerts = mock(StockAlertRepository.class);
        when(snapshots.findLatestWithStocksByOwnerUserId(7L)).thenReturn(Optional.empty());
        when(alerts.findDistinctStockCodeMarketByOwnerUserId(7L)).thenReturn(List.of());
        ReflectionTestUtils.setField(target, "snapshotRepo", snapshots);
        ReflectionTestUtils.setField(target, "alertRepo", alerts);

        var productionAttributes = new AnnotationTransactionAttributeSource();
        TransactionAttributeSource attributes = (method, type) -> {
            TransactionAttribute actual = productionAttributes.getTransactionAttribute(method, type);
            if (!legacyRequired || actual == null) return actual;
            var baseline = new DefaultTransactionAttribute(actual);
            baseline.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            return baseline;
        };
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(attributes);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            if (!invocation.getMethod().getName().equals("getStockDetailForOwner")) return invocation.proceed();
            innerTransactions.add(transactionFrame());
            assertOwnerDateLockHeld();
            try {
                return invocation.proceed();
            } catch (RuntimeException missing) {
                missingTargets.add(missing);
                throw missing;
            }
        });
        return (TradingRadarService) proxy.getProxy();
    }

    private Frozen captureMissingRadar(TradingRadarService radar) {
        var original = frozen(7, Instant.now(), "09:05", "task516");
        ObjectNode input = original.input(), vector = original.sourceVector();
        input.withObject("sources").remove("market:台股:00850");
        vector.remove("market:台股:00850");
        original = new Frozen(original.facts(), input, vector, original.assetSnapshotId(), original.assetGeneratedAt());
        ObjectNode model = MAPPER.createObjectNode();
        model.putObject("detailed_target_allocation_2027").put("00850", 0);
        var adapter = new SrppReportExtraCaptureAdapter(mock(PriceQueryService.class), mock(MarketDataService.class),
                radar, mock(StockPriceHistoryRepository.class), mock(StockDividendHistoryRepository.class),
                mock(TechnicalIndicatorService.class), MAPPER);
        var extra = adapter.capture(7, DATE, "09:05", original.facts().capturedAt(), model,
                original.input().path("sources").path("assets"), original.input().path("sources"));
        assertThat(extra.sources().path("market:台股:00850").path("radar").isNull()).isTrue();
        assertThat(extra.sourceVector().path("market:台股:00850").path("validation").asText()).isEqualTo("UNAVAILABLE");
        return SrppReportProducerScheduler.finishCapture(original, extra.sources(), extra.sourceVector(), Instant.now());
    }

    private TxFrame transactionFrame() {
        return jdbc.queryForObject("SELECT txid_current(), pg_backend_pid(), current_setting('transaction_read_only')",
                (row, index) -> new TxFrame(row.getLong(1), row.getInt(2), row.getString(3)));
    }

    private void assertOwnerDateLockHeld() {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?,?)")) {
                statement.setInt(1, 7);
                statement.setInt(2, Math.toIntExact(DATE.toEpochDay()));
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isFalse();
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot verify publication fence", failure);
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private record TxFrame(long transactionId, int backendPid, String readOnly) {}
}
