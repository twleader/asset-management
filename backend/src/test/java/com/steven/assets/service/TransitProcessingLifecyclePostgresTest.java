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
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    @MockitoBean MarketDataService marketDataService;
    @MockitoBean StockMasterService stockMasterService;
    @MockitoBean FundNavService fundNavService;
    @MockitoBean FundDividendService fundDividendService;
    @MockitoBean AssetClassifier assetClassifier;
    @MockitoBean SnapshotStockScopeOwnershipPort stockScopeOwnershipPort;
    @MockitoBean InstitutionService institutionService;

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
    void sameDaySettlementAdjustsOnlyLatestBalancesAndSecondRunEmitsNoDml() {
        Long history = snapshot(OWNER, today.minusDays(30), auto("-200", today.minusDays(10)));
        BankDeposit usd = BankDeposit.builder().depositType("美元活存").currency("USD")
                .amount(new BigDecimal("100")).originalAmount(new BigDecimal("100")).build();
        BankDeposit usdTransit = manual("20", "TRANSIT_USD", today.minusDays(1));
        usdTransit.setOriginalAmount(new BigDecimal("20"));
        Long latest = snapshot(OWNER, today, ordinary("1000"), usd, auto("-31", today),
                usdTransit, auto("-40", today.plusDays(1)),
                manual("-9", "TRANSIT_TWD", null));
        Long other = snapshot(OTHER_OWNER, today, auto("-88", today));
        assertThat(assets.getSnapshotDetail(latest).deposits()).hasSize(6); // GET does not expire anything.

        trace.start();
        assertThat(assets.rollLatestSnapshotToTodayForOwner(OWNER, today)).isTrue();
        List<String> firstSql = trace.stop();
        assertThat(firstSql.getFirst()).contains("owner_user_id", "for no key update");
        assertThat(firstSql).anyMatch(sql -> sql.startsWith("delete from bank_deposit"));
        read(latest, s -> {
            assertThat(s.getDeposits()).hasSize(4);
            assertThat(s.getTotalDeposit()).isEqualByComparingTo("1040");
            assertThat(s.getTotalAssets()).isEqualByComparingTo("1040");
            assertThat(s.getEstimatedAnnualDividend()).isEqualByComparingTo("15");
            assertThat(s.getDeposits().stream().filter(d -> "TWD".equals(d.getCurrency())).findFirst().orElseThrow()
                    .getAmount()).isEqualByComparingTo("969");
            assertThat(s.getDeposits().stream().filter(d -> "USD".equals(d.getCurrency())).findFirst().orElseThrow()
                    .getOriginalAmount()).isEqualByComparingTo("120");
        });
        read(history, s -> assertThat(s.getDeposits()).hasSize(1));
        read(other, s -> assertThat(s.getDeposits()).hasSize(1));
        trace.start();
        assertThat(assets.rollLatestSnapshotToTodayForOwner(OWNER, today)).isFalse();
        assertThat(trace.stop()).noneMatch(SqlTrace::isDml);
    }

    @Test
    void startupRecoveryPathRollsOldLatestAndLeavesFutureSnapshotUntouched() {
        Long stale = snapshot(OWNER, today.minusDays(3), ordinary("100"), auto("-31", today.minusDays(2)), auto("-40", today.plusDays(1)));
        Long future = snapshot(OTHER_OWNER, today.plusDays(1), auto("-88", today));
        assertThat(assets.rollLatestSnapshotToTodayForOwner(OWNER, today)).isTrue();
        assertThat(assets.rollLatestSnapshotToTodayForOwner(OTHER_OWNER, today)).isFalse();
        read(stale, s -> {
            assertThat(s.getSnapshotDate()).isEqualTo(today);
            assertThat(s.getDeposits()).hasSize(2);
            assertThat(s.getDeposits().stream().filter(d -> "TRANSIT_TWD".equals(d.getCurrency())).findFirst()
                    .orElseThrow().getProcessingDate()).isEqualTo(today.plusDays(1));
            assertThat(s.getTotalAssets()).isEqualByComparingTo("29");
        });
        read(future, s -> {
            assertThat(s.getSnapshotDate()).isEqualTo(today.plusDays(1));
            assertThat(s.getDeposits()).hasSize(1);
        });
    }

    @Test
    void supplementLegacyAutoExpiresImmediatelyAndStaleFormCannotResurrectIt() {
        Long id = snapshot(OWNER, today, auto("-31", null), auto("-40", today.plusDays(1)), ordinary("100"));
        var oldDetail = assets.getSnapshotDetail(id);
        var due = oldDetail.deposits().stream().filter(d -> "AUTO".equals(d.updateMode()) && d.processingDate() == null)
                .findFirst().orElseThrow();
        var future = oldDetail.deposits().stream().filter(d -> d.processingDate() != null).findFirst().orElseThrow();
        var cash = oldDetail.deposits().stream().filter(d -> "TWD".equals(d.currency())).findFirst().orElseThrow();
        var request = request(today, List.of(request(due, today), request(future, null), request(cash, null)), "new note");
        assertThat(assets.updateSnapshot(id, request).totalDeposit()).isEqualByComparingTo("29");
        assertThat(deposits.existsById(due.id())).isFalse();
        assertThatThrownBy(() -> assets.updateSnapshot(id, request)).isInstanceOf(IllegalArgumentException.class);
        read(id, s -> {
            assertThat(s.getDeposits()).hasSize(2);
            assertThat(s.getDeposits().getFirst().getId()).isEqualTo(future.id());
            assertThat(s.getDeposits().getFirst().getProcessingDate()).isEqualTo(today.plusDays(1));
            assertThat(s.getDeposits().get(1).getAmount()).isEqualByComparingTo("69");
            assertThat(s.getTotalAssets()).isEqualByComparingTo("29");
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
            assertThat(s.getDeposits().stream().filter(d -> "TWD".equals(d.getCurrency())).findFirst()
                    .orElseThrow().getAmount()).isEqualByComparingTo("100");
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
        var cash = new AssetSnapshotDto.DepositRequest(bank.getId(), "活存", new BigDecimal("100"),
                null, "TWD", null, null);
        var created = assets.createSnapshot(request(today, List.of(due, cash), "current"));
        assertThat(created.totalAssets()).isEqualByComparingTo("110");
        read(created.id(), s -> assertThat(s.getDeposits()).singleElement().satisfies(row ->
                assertThat(row.getAmount()).isEqualByComparingTo("110")));
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
    void fullPutCannotDowngradeOrOmitSourceManagedCashForEitherCurrency() {
        for (String currency : List.of("TWD", "USD")) {
            BankDeposit managed = "USD".equals(currency)
                    ? BankDeposit.builder().depositType("美元活存").currency("USD")
                        .amount(new BigDecimal("100")).originalAmount(new BigDecimal("100")).build()
                    : ordinary("100");
            managed.setSource("FUBON_SYNC");
            managed.setNotes("managed cash");
            BankDeposit due = manual("10", "TRANSIT_" + currency, today);
            due.setOriginalAmount(BigDecimal.TEN);
            Long id = snapshot(OWNER, today, managed, due);
            var detail = assets.getSnapshotDetail(id);
            var original = detail.deposits().getFirst();
            var transit = detail.deposits().get(1);
            var exactIdAttack = new AssetSnapshotDto.DepositRequest(null, "renamed", BigDecimal.ONE,
                    null, "OTHER", null, "overwrite", original.id(), null);
            var identityAttack = new AssetSnapshotDto.DepositRequest(bank.getId(), original.depositType(),
                    BigDecimal.ONE, null, "OTHER", null, "overwrite");
            // 完整 PUT 省略 managed、再以 exact id 及 bank+type 嘗試重建，三者都保持原列。
            for (List<AssetSnapshotDto.DepositRequest> payload : List.of(
                    List.of(request(transit, today)), List.of(request(transit, today), exactIdAttack),
                    List.of(request(transit, today), identityAttack))) {
                // 手動在途每次 PUT 會換 id，使用最新 persisted id。
                var currentTransit = assets.getSnapshotDetail(id).deposits().stream()
                        .filter(d -> d.currency().startsWith("TRANSIT_")).findFirst().orElseThrow();
                var currentPayload = new ArrayList<>(payload);
                currentPayload.set(0, request(currentTransit, today));
                assets.updateSnapshot(id, request(today, currentPayload, "updated"));
                read(id, snapshot -> {
                    assertThat(snapshot.getDeposits()).hasSize(2);
                    var actual = snapshot.getDeposits().stream().filter(d -> d.getId().equals(original.id()))
                            .findFirst().orElseThrow();
                    assertThat(actual.getSource()).isEqualTo("FUBON_SYNC");
                    assertThat(actual.getAmount()).isEqualByComparingTo("100");
                    assertThat(actual.getNotes()).isEqualTo("managed cash");
                    assertThat(actual.getCurrency()).isEqualTo(currency);
                    assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("110");
                });
            }
            trace.start();
            assertThat(assets.rollLatestSnapshotToTodayForOwner(OWNER, today)).isFalse();
            assertThat(trace.stop()).noneMatch(SqlTrace::isDml);
            tx(() -> { snapshots.deleteById(id); snapshots.flush(); });
        }
    }

    @Test
    void concurrentRollsWaitOnParentLockAndApplySettlementOnlyOnce() throws Exception {
        Long id = snapshot(OWNER, today, ordinary("100"), auto("-31", today));
        CountDownLatch firstSettled = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tx(() -> {
                assertThat(assets.rollLatestSnapshotToTodayForOwner(OWNER, today)).isTrue();
                entityManager.flush();
                firstSettled.countDown();
                await(releaseFirst);
            }));
            try {
                assertThat(firstSettled.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    trace.start();
                    secondStarted.countDown();
                    boolean changed = assets.rollLatestSnapshotToTodayForOwner(OWNER, today);
                    var sql = trace.stop();
                    assertThat(sql.getFirst()).contains("for no key update");
                    assertThat(sql).noneMatch(SqlTrace::isDml);
                    return changed;
                });
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                first.get(10, TimeUnit.SECONDS);
                assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
            } finally {
                releaseFirst.countDown();
            }
        }
        read(id, snapshot -> {
            assertThat(snapshot.getDeposits()).singleElement().satisfies(row ->
                    assertThat(row.getAmount()).isEqualByComparingTo("69"));
            assertThat(snapshot.getTotalAssets()).isEqualByComparingTo("69");
            assertThat(snapshot.getEstimatedAnnualDividend()).isEqualByComparingTo("1");
        });
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
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
        return BankDeposit.builder().depositType("活存").amount(new BigDecimal(amount)).currency("TWD")
                .annualInterestRate(new BigDecimal("1.5")).build();
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
