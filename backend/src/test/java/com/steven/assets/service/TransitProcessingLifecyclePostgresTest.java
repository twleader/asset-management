package com.steven.assets.service;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BankDeposit;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BankDepositRepository;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.TenantAccessException;
import com.steven.assets.security.TenantGuard;
import jakarta.persistence.EntityManager;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Real PostgreSQL transactions and orphan removal; no vendor client or real account is loaded. */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AssetService.class, AssetSnapshotMutationLock.class, SnapshotAggregateCalculator.class,
        ExcelImportService.class, TransitProcessingLifecyclePostgresTest.Config.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransitProcessingLifecyclePostgresTest {
    private static final long OWNER = 91L;
    private static final long OTHER_OWNER = 92L;
    private static final String PAYABLE = "買股待付款";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transit_processing_lifecycle")
            .withUsername("assets").withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean MarketDataService marketDataService;
    @MockBean StockMasterService stockMasterService;
    @MockBean FundNavService fundNavService;
    @MockBean FundDividendService fundDividendService;
    @MockBean AssetClassifier assetClassifier;
    @MockBean SnapshotStockScopeOwnershipPort stockScopeOwnershipPort;
    @MockBean InstitutionService institutionService;

    @Autowired AssetService assets;
    @Autowired ExcelImportService excel;
    @Autowired AssetSnapshotRepository snapshots;
    @Autowired BankDepositRepository deposits;
    @Autowired BankRepository banks;
    @Autowired SnapshotAggregateCalculator aggregates;
    @Autowired CurrentUserContext currentUser;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired SqlTrace trace;

    private LocalDate today;
    private Bank bank;

    @BeforeEach
    void seed() {
        today = TransitProcessingDatePolicy.today(Clock.systemUTC());
        currentUser.setEffectiveUserId(OWNER);
        trace.stop();
        tx(() -> {
            entityManager.createNativeQuery("DROP TRIGGER IF EXISTS reject_transit_delete ON bank_deposit").executeUpdate();
            entityManager.createNativeQuery("DROP FUNCTION IF EXISTS reject_transit_delete()").executeUpdate();
            snapshots.deleteAll();
            snapshots.flush();
            banks.deleteAll();
            banks.flush();
            bank = banks.saveAndFlush(Bank.builder().code("fubon").displayName("台北富邦銀行").active(true).build());
        });
        when(stockScopeOwnershipPort.capture(any())).thenReturn(SnapshotStockScopeOwnership.payloadOwned());
    }

    @Test
    void sameDayCleanupDeletesOnlyLatestDueTransitAndSecondRunEmitsNoDml() {
        Long history = snapshot(OWNER, today.minusDays(30), auto("-200", today.minusDays(10)));
        Long latest = snapshot(OWNER, today, ordinary("1000"), auto("-31", today),
                manual("20", "TRANSIT_USD", today.minusDays(1)), auto("-40", today.plusDays(1)),
                manual("-9", "TRANSIT_TWD", null));
        Long other = snapshot(OTHER_OWNER, today, auto("-88", today));
        assertThat(assets.getSnapshotDetail(latest).deposits()).hasSize(5); // GET does not expire anything.

        trace.start();
        assertThat(assets.rollLatestSnapshotToTodayForOwner(OWNER, today)).isTrue();
        List<String> firstSql = trace.stop();
        assertThat(firstSql.getFirst()).contains("owner_user_id", "for no key update");
        assertThat(firstSql).anyMatch(sql -> sql.startsWith("delete from bank_deposit"));
        read(latest, s -> {
            assertThat(s.getDeposits()).hasSize(3);
            assertThat(s.getTotalDeposit()).isEqualByComparingTo("951");
            assertThat(s.getTotalAssets()).isEqualByComparingTo("951");
            assertThat(s.getDeposits().stream().filter(d -> "TWD".equals(d.getCurrency())).findFirst().orElseThrow()
                    .getAmount()).isEqualByComparingTo("1000");
        });
        read(history, s -> assertThat(s.getDeposits()).hasSize(1));
        read(other, s -> assertThat(s.getDeposits()).hasSize(1));
        trace.start();
        assertThat(assets.rollLatestSnapshotToTodayForOwner(OWNER, today)).isFalse();
        assertThat(trace.stop()).noneMatch(SqlTrace::isDml);
    }

    @Test
    void startupRecoveryPathRollsOldLatestAndLeavesFutureSnapshotUntouched() {
        Long stale = snapshot(OWNER, today.minusDays(3), auto("-31", today.minusDays(2)), auto("-40", today.plusDays(1)));
        Long future = snapshot(OTHER_OWNER, today.plusDays(1), auto("-88", today));
        assertThat(assets.rollLatestSnapshotToTodayForOwner(OWNER, today)).isTrue();
        assertThat(assets.rollLatestSnapshotToTodayForOwner(OTHER_OWNER, today)).isFalse();
        read(stale, s -> {
            assertThat(s.getSnapshotDate()).isEqualTo(today);
            assertThat(s.getDeposits()).singleElement().extracting(BankDeposit::getProcessingDate).isEqualTo(today.plusDays(1));
            assertThat(s.getTotalAssets()).isEqualByComparingTo("-40");
        });
        read(future, s -> {
            assertThat(s.getSnapshotDate()).isEqualTo(today.plusDays(1));
            assertThat(s.getDeposits()).hasSize(1);
        });
    }

    @Test
    void supplementLegacyAutoExpiresImmediatelyAndStaleFormCannotResurrectIt() {
        Long id = snapshot(OWNER, today, auto("-31", null), auto("-40", today.plusDays(1)));
        var oldDetail = assets.getSnapshotDetail(id);
        var due = oldDetail.deposits().stream().filter(d -> d.processingDate() == null).findFirst().orElseThrow();
        var future = oldDetail.deposits().stream().filter(d -> d.processingDate() != null).findFirst().orElseThrow();
        var request = request(today, List.of(request(due, today), request(future, null)), "new note");
        assertThat(assets.updateSnapshot(id, request).totalDeposit()).isEqualByComparingTo("-40");
        assertThat(deposits.existsById(due.id())).isFalse();
        assertThatThrownBy(() -> assets.updateSnapshot(id, request)).isInstanceOf(IllegalArgumentException.class);
        read(id, s -> {
            assertThat(s.getDeposits()).singleElement().extracting(BankDeposit::getId).isEqualTo(future.id());
            assertThat(s.getDeposits().getFirst().getProcessingDate()).isEqualTo(today.plusDays(1));
            assertThat(s.getTotalAssets()).isEqualByComparingTo("-40");
        });
    }

    @Test
    void foreignDuplicateAndUnknownIdsRejectWholePutBeforeAnyChildMutation() {
        Long id = snapshot(OWNER, today, auto("-31", null), ordinary("100"));
        Long other = snapshot(OTHER_OWNER, today, auto("-50", null));
        var own = assets.getSnapshotDetail(id);
        Long foreignDeposit = new TransactionTemplate(transactions).execute(status ->
                snapshots.findById(other).orElseThrow().getDeposits().getFirst().getId());
        var valid = request(own.deposits().getFirst(), today);
        for (Long invalid : List.of(foreignDeposit, Long.MAX_VALUE, valid.id())) {
            var bad = new AssetSnapshotDto.DepositRequest(bank.getId(), PAYABLE, BigDecimal.ONE, null,
                    "TRANSIT_TWD", null, null, invalid, today);
            trace.start();
            assertThatThrownBy(() -> assets.updateSnapshot(id, request(today, List.of(valid, bad), "must rollback")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(trace.stop()).noneMatch(SqlTrace::isDml);
            read(id, s -> {
                assertThat(s.getDeposits()).hasSize(2);
                assertThat(s.getNotes()).isEqualTo("original");
                assertThat(s.getDeposits()).allMatch(d -> d.getProcessingDate() == null);
                assertThat(s.getTotalAssets()).isEqualByComparingTo("69");
            });
        }
        assertThatThrownBy(() -> assets.updateSnapshot(other, request(today, List.of(), "wrong owner")))
                .isInstanceOf(TenantAccessException.class);
    }

    @Test
    void failingOrphanDeleteRollsBackSupplementNotesAndAggregates() {
        Long id = snapshot(OWNER, today, auto("-31", null), ordinary("100"));
        var detail = assets.getSnapshotDetail(id);
        tx(() -> {
            entityManager.createNativeQuery("CREATE FUNCTION reject_transit_delete() RETURNS trigger LANGUAGE plpgsql AS $$ "
                    + "BEGIN RAISE EXCEPTION 'synthetic delete failure'; END $$").executeUpdate();
            entityManager.createNativeQuery("CREATE TRIGGER reject_transit_delete BEFORE DELETE ON bank_deposit "
                    + "FOR EACH ROW EXECUTE FUNCTION reject_transit_delete()").executeUpdate();
        });
        assertThatThrownBy(() -> assets.updateSnapshot(id, request(today,
                detail.deposits().stream().map(d -> request(d, today)).toList(), "must rollback")))
                .isInstanceOf(RuntimeException.class);
        read(id, s -> {
            assertThat(s.getDeposits()).hasSize(2);
            assertThat(s.getDeposits()).allMatch(d -> d.getProcessingDate() == null);
            assertThat(s.getNotes()).isEqualTo("original");
            assertThat(s.getTotalAssets()).isEqualByComparingTo("69");
        });
    }

    @Test
    void historicalAndFuturePutsKeepTheirDueTransitWhileNewCurrentSnapshotExpiresIt() {
        Long historic = snapshot(OWNER, today.minusDays(2), auto("-31", today.minusDays(1)));
        snapshot(OWNER, today.minusDays(1), ordinary("100"));
        assets.updateSnapshot(historic, request(today.minusDays(2), List.of(), "history"));
        read(historic, s -> assertThat(s.getDeposits()).hasSize(1));
        var due = new AssetSnapshotDto.DepositRequest(bank.getId(), "賣股待收款", BigDecimal.TEN,
                null, "TRANSIT_TWD", null, null, null, today);
        var created = assets.createSnapshot(request(today, List.of(due), "current"));
        assertThat(created.totalAssets()).isZero();
        var future = assets.createSnapshot(request(today.plusDays(1), List.of(due), "future"));
        var futureDetail = assets.getSnapshotDetail(future.id());
        assets.updateSnapshot(future.id(), request(today.plusDays(1),
                List.of(request(futureDetail.deposits().getFirst(), today)), "future"));
        read(future.id(), s -> assertThat(s.getDeposits()).hasSize(1));
        var older = assets.createSnapshot(request(today.minusDays(5), List.of(due), "older"));
        assertThat(older.totalAssets()).isEqualByComparingTo("10");
    }

    @Test
    void actualExcelSameDateImportPreservesEveryAutoDateAndIdentity() throws Exception {
        Long id = snapshot(OWNER, today, auto("-31", today.plusDays(1)), auto("-40", today.plusDays(2)));
        List<AssetSnapshotDto.DepositResponse> original = assets.getSnapshotDetail(id).deposits();
        when(institutionService.matchBankByKeyword("台北富邦銀行")).thenReturn(Optional.of(bank));
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(today.format(DateTimeFormatter.BASIC_ISO_DATE));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("台北富邦銀行");
            row.createCell(1).setCellValue("活存");
            row.createCell(2).setCellValue(100);
            workbook.write(out);
            bytes = out.toByteArray();
        }
        var result = excel.importExcel(new MockMultipartFile("file", "legacy.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes));
        assertThat(result.errors()).isEmpty();
        assertThat(result.snapshotsImported()).isEqualTo(1);
        var after = assets.getSnapshotDetail(id);
        assertThat(after.deposits().stream().filter(d -> "AUTO".equals(d.updateMode())).toList()).isEqualTo(original);
        assertThat(after.totalDeposit()).isEqualByComparingTo("29");
    }

    @Test
    void migrationIsIdempotentKeepsLegacyNullAndRejectsOrdinaryDepositDates() throws Exception {
        String migration;
        try (var source = getClass().getResourceAsStream("/db/changelog/changes/v1.135.0-transit-processing-date.sql")) {
            migration = new String(java.util.Objects.requireNonNull(source).readAllBytes(), StandardCharsets.UTF_8);
        }
        tx(() -> {
            // 獨立 schema 重現舊表，避免 DDL 改變共用 JPA 連線的 prepared statement 結果型別。
            entityManager.createNativeQuery("CREATE SCHEMA transit_processing_migration").executeUpdate();
            entityManager.createNativeQuery("SET LOCAL search_path TO transit_processing_migration").executeUpdate();
            entityManager.createNativeQuery("CREATE TABLE bank_deposit(id bigint PRIMARY KEY, currency varchar(20))").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO bank_deposit VALUES (1, 'TRANSIT_TWD'), (2, 'TWD')").executeUpdate();
            entityManager.createNativeQuery(migration).executeUpdate();
            entityManager.createNativeQuery(migration).executeUpdate();
            assertThat(((Number) entityManager.createNativeQuery(
                    "SELECT count(*) FROM bank_deposit WHERE processing_date IS NULL").getSingleResult()).intValue())
                    .isEqualTo(2);
        });
        assertThatThrownBy(() -> tx(() -> entityManager.createNativeQuery("UPDATE transit_processing_migration.bank_deposit "
                + "SET processing_date = CURRENT_DATE WHERE currency = 'TWD'").executeUpdate()))
                .isInstanceOf(RuntimeException.class);
        tx(() -> entityManager.createNativeQuery("DROP SCHEMA transit_processing_migration CASCADE").executeUpdate());
    }

    private Long snapshot(long owner, LocalDate date, BankDeposit... rows) {
        return new TransactionTemplate(transactions).execute(status -> {
            AssetSnapshot snapshot = AssetSnapshot.builder().ownerUserId(owner).snapshotDate(date)
                    .usdExchangeRate(BigDecimal.ONE).notes("original").build();
            for (BankDeposit row : rows) {
                row.setSnapshot(snapshot);
                row.setBank(bank);
                snapshot.getDeposits().add(row);
            }
            aggregates.recalculate(snapshot);
            return snapshots.saveAndFlush(snapshot).getId();
        });
    }

    private BankDeposit auto(String amount, LocalDate processingDate) {
        return BankDeposit.builder().depositType(PAYABLE).amount(new BigDecimal(amount)).currency("TRANSIT_TWD")
                .source("FUBON_SYNC").processingDate(processingDate).notes("source note").build();
    }

    private BankDeposit manual(String amount, String currency, LocalDate processingDate) {
        return BankDeposit.builder().depositType("其他款項").amount(new BigDecimal(amount)).currency(currency)
                .source("MANUAL").processingDate(processingDate).build();
    }

    private BankDeposit ordinary(String amount) {
        return BankDeposit.builder().depositType("活存").amount(new BigDecimal(amount)).currency("TWD").build();
    }

    private AssetSnapshotDto.DepositRequest request(AssetSnapshotDto.DepositResponse deposit, LocalDate date) {
        return new AssetSnapshotDto.DepositRequest(deposit.bankId(), deposit.depositType(), deposit.amount(),
                deposit.originalAmount(), deposit.currency(), deposit.annualInterestRate(), deposit.notes(), deposit.id(), date);
    }

    private AssetSnapshotDto.CreateSnapshotRequest request(LocalDate date,
            List<AssetSnapshotDto.DepositRequest> deposits, String notes) {
        return new AssetSnapshotDto.CreateSnapshotRequest(date, BigDecimal.ONE, notes, deposits, List.of(), List.of());
    }

    private void read(Long id, Consumer<AssetSnapshot> assertion) {
        tx(() -> assertion.accept(snapshots.findById(id).orElseThrow()));
    }

    private void tx(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> action.run());
    }

    static class SqlTrace implements StatementInspector {
        private final ThreadLocal<List<String>> active = new ThreadLocal<>();
        void start() { active.set(new ArrayList<>()); }
        List<String> stop() {
            List<String> result = active.get();
            active.remove();
            return result == null ? List.of() : List.copyOf(result);
        }
        @Override public String inspect(String sql) {
            if (active.get() != null) active.get().add(sql.toLowerCase());
            return sql;
        }
        static boolean isDml(String sql) {
            return sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete");
        }
    }

    @TestConfiguration
    static class Config {
        @Bean CurrentUserContext currentUserContext() { return new CurrentUserContext(); }
        @Bean TenantGuard tenantGuard(CurrentUserContext currentUser) { return new TenantGuard(currentUser); }
        @Bean SqlTrace sqlTrace() { return new SqlTrace(); }
        @Bean HibernatePropertiesCustomizer tracing(SqlTrace trace) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", trace);
        }
    }
}
