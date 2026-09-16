package com.steven.assets.service;

import com.steven.assets.dto.*;
import com.steven.assets.model.*;
import com.steven.assets.repository.ExportScheduleFreshRead;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.service.export.DualFormatExportWriter;
import com.steven.assets.service.export.ExcelDocRenderer;
import com.steven.assets.service.export.JsonDocRenderer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real commits and row locks; document/render/file/Drive work is synthetic and never accesses production. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ExportScheduleFreshRead.class, ExportScheduleExecutionStore.class, CommodityExportScheduleExecutionStore.class,
        RealizedGainExportScheduleExecutionStore.class, IndexExportScheduleExecutionStore.class,
        ExportScheduleService.class, CommodityExportScheduleService.class, RealizedGainExportScheduleService.class,
        IndexExportScheduleService.class, ExportScheduleWritebackPostgresTest.Config.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExportScheduleWritebackPostgresTest {
    enum Kind {
        ASSETS(ExportScheduleSetting.class, ExportScheduleTime.class),
        COMMODITY(CommodityExportSchedule.class, CommodityExportScheduleTime.class),
        GAIN(RealizedGainExportSchedule.class, RealizedGainExportScheduleTime.class),
        INDEX(IndexExportSchedule.class, IndexExportScheduleTime.class);
        final Class<?> model, child;
        Kind(Class<?> model, Class<?> child) { this.model = model; this.child = child; }
    }
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("export_writeback_fixture").withUsername("fixture").withPassword("fixture-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl); r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
    @TestConfiguration @EnableAspectJAutoProxy static class Config {
        @Bean TxProbe txProbe() { return new TxProbe(); }
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor disableFixtureScheduling() {
            return factory -> {
                String name = org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                if (factory instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry
                        && registry.containsBeanDefinition(name)) registry.removeBeanDefinition(name);
            };
        }
    }
    @Aspect static class TxProbe {
        final AtomicInteger reads = new AtomicInteger();
        @Before("execution(* com.steven.assets.repository.ExportScheduleFreshRead.refresh(..))")
        public void lockedRefresh() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            reads.incrementAndGet();
        }
    }
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager tm;
    @Autowired ExportScheduleExecutionStore assets;
    @Autowired CommodityExportScheduleExecutionStore commodity;
    @Autowired RealizedGainExportScheduleExecutionStore gain;
    @Autowired IndexExportScheduleExecutionStore index;
    @Autowired ExportScheduleService assetsService;
    @Autowired CommodityExportScheduleService commodityService;
    @Autowired RealizedGainExportScheduleService gainService;
    @Autowired IndexExportScheduleService indexService;
    @Autowired TxProbe probe;
    @MockBean ExcelExportService excel;
    @MockBean ExcelDocRenderer xlsx;
    @MockBean JsonDocRenderer json;
    @MockBean DualFormatExportWriter writer;
    @MockBean GdriveOutputSupport drive;
    @MockBean CurrentUserContext user;
    private static final AtomicLong OWNERS = new AtomicLong(1000);
    private static final LocalDate D1 = LocalDate.of(2026, 9, 15), D2 = D1.plusDays(1);
    private static final LocalDateTime COMPLETED = D2.atTime(12, 0);
    private Long owner;

    @BeforeEach void setup() throws Exception {
        owner = OWNERS.incrementAndGet();
        when(user.hasUser()).thenReturn(true); when(user.getEffectiveUserId()).thenReturn(owner);
        when(drive.resolveUpdate(anyLong(), nullable(Boolean.class), nullable(String.class), anyBoolean(), nullable(String.class)))
            .thenAnswer(i -> new GdriveOutputSupport.DriveSettings(i.getArgument(1) == null ? i.getArgument(3) : i.getArgument(1),
                    i.getArgument(2) == null ? i.getArgument(4) : i.getArgument(2), null));
        when(drive.syncQuietly(anyLong(), nullable(String.class), isNull()))
            .thenReturn(new GdriveOutputSupport.SyncResult("跳過：本輪未產生本機檔案", null));
        fakeWriter(() -> new DualFormatExportWriter.DualResult(null, null, "成功：synthetic", "失敗：synthetic Drive", null, null));
    }
    @SuppressWarnings("unchecked") private ExportExecutionPort<Object> port(Kind k) {
        return (ExportExecutionPort<Object>) (ExportExecutionPort<?>) switch (k) {
            case ASSETS -> assets; case COMMODITY -> commodity; case GAIN -> gain; case INDEX -> index;
        };
    }
    private Object service(Kind k) {
        return switch (k) { case ASSETS -> assetsService; case COMMODITY -> commodityService;
            case GAIN -> gainService; case INDEX -> indexService; };
    }
    private <T> T tx(Supplier<T> body) { return new TransactionTemplate(tm).execute(s -> body.get()); }
    private static Object get(Object bean, String field) { return new BeanWrapperImpl(bean).getPropertyValue(field); }
    private static void set(Object bean, String field, Object value) { new BeanWrapperImpl(bean).setPropertyValue(field, value); }
    @SuppressWarnings("unchecked") private static List<Object> times(Object parent) { return (List<Object>) get(parent, "times"); }
    private Object newChild(Kind k, Object parent, int hour) {
        try {
            Object child = k.child.getConstructor().newInstance();
            set(child, "schedule", parent); set(child, "runHour", hour); set(child, "runMinute", 0);
            set(child, "enabled", true);
            if (k == Kind.INDEX) set(child, "markets", new LinkedHashSet<>(Set.of("TWSE")));
            return child;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private Long fixture(Kind k, boolean legacy) {
        return tx(() -> {
            try {
                Object parent = k.model.getConstructor().newInstance();
                set(parent, "ownerUserId", owner); set(parent, "enabled", true);
                set(parent, "outputSubpath", "old-local"); set(parent, "gdriveEnabled", true); set(parent, "gdriveSubpath", "old-drive");
                if (!legacy) times(parent).add(newChild(k, parent, 8));
                em.persist(parent); em.flush(); return (Long) get(parent, "id");
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
    private CapturedExport capture(Kind k, Long id, LocalDate day) {
        int before = probe.reads.get();
        var rows = port(k).due(id, owner, day, LocalTime.NOON);
        assertThat(probe.reads.get()).isGreaterThan(before);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return rows.get(0);
    }
    private Map<String, Object> read(Kind k, Long id) {
        return tx(() -> {
            em.clear(); Object p = em.find(k.model, id);
            if (p == null) return Map.of();
            Map<String, Object> result = new HashMap<>();
            for (String field : List.of("outputSubpath", "gdriveSubpath", "gdriveLastRunAt", "gdriveLastStatus")) result.put(field, get(p, field));
            result.put("childCount", times(p).size());
            if (!times(p).isEmpty()) {
                Object c = times(p).get(0);
                for (String field : List.of("id", "runHour", "lastRunDate", "lastRunAt", "lastRunStatus", "enabled", "updatedAt")) result.put("child." + field, get(c, field));
            }
            if (k != Kind.INDEX) for (String field : List.of("runHour", "lastRunDate", "lastRunAt", "lastRunStatus")) result.put(field, get(p, field));
            return result;
        });
    }
    private void complete(Kind k, CapturedExport c, LocalDateTime at, String status, String driveStatus) {
        int before = probe.reads.get(); port(k).complete(c, at, status, driveStatus);
        assertThat(probe.reads.get()).isGreaterThan(before);
    }
    private void edit(Kind k, Consumer<Object> change) {
        port(k).update(owner, p -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); change.accept(p); return null; });
    }
    private void fakeWriter(Supplier<DualFormatExportWriter.DualResult> outcome) throws IOException {
        when(writer.write(anyLong(), any(Path.class), anyString(), nullable(byte[].class), nullable(byte[].class), anyBoolean(), nullable(String.class)))
                .thenAnswer(i -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return outcome.get(); });
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private void scheduled(Kind k, CapturedExport c) {
        Object target = AopTestUtils.getUltimateTargetObject(service(k));
        ReflectionTestUtils.invokeMethod(target, "runScheduled", c);
    }
    private void uiSave(Kind k, int hour, boolean enabled, String local, String remote) {
        switch (k) {
            case ASSETS -> assetsService.updateForCurrentUser(new ExportScheduleDto.SettingRequest(true, local, false, remote,
                    List.of(new ExportScheduleDto.TimeRequest(hour, 0, enabled))));
            case COMMODITY -> commodityService.updateForCurrentUser(new CommodityExportDto.SettingRequest(true, local, 6,
                    List.of(new CommodityExportDto.TimeRequest(hour, 0, enabled)), false, remote));
            case GAIN -> gainService.updateForCurrentUser(new RealizedGainExportDto.SettingRequest(true, local, false, remote,
                    List.of(new RealizedGainExportDto.TimeRequest(hour, 0, enabled))));
            case INDEX -> indexService.updateForCurrentUser(new IndexExportDto.SettingRequest(true, local, 6,
                    List.of(new IndexExportDto.TimeRequest(hour, 0, enabled, List.of("TWSE"))), false, remote));
        }
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void lateD1FinishesAfterD2ThroughActualScheduledServiceWithoutRollingDateBack(Kind k) throws Exception {
        Long id = fixture(k, false); CapturedExport d1 = capture(k, id, D1), d2 = capture(k, id, D2);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        fakeWriter(() -> {
            int call = calls.incrementAndGet();
            if (call == 1) { started.countDown(); await(release); }
            return new DualFormatExportWriter.DualResult(Path.of("/synthetic/" + call + ".json"), Path.of("/synthetic/" + call + ".xlsx"),
                    "成功：" + call, "Drive：" + call, null, null);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> late = executor.submit(() -> scheduled(k, d1)); await(started);
            scheduled(k, d2); LocalDateTime d2At = (LocalDateTime) read(k, id).get("child.lastRunAt");
            release.countDown(); late.get(5, TimeUnit.SECONDS);
            Map<String, Object> current = read(k, id);
            assertThat(current.get("child.lastRunDate")).isEqualTo(D2);
            assertThat((LocalDateTime) current.get("child.lastRunAt")).isAfterOrEqualTo(d2At);
            assertThat(current.get("child.lastRunStatus").toString()).contains("1");
            assertThat(port(k).due(id, owner, D2, LocalTime.MAX)).isEmpty();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void actualUiSaveWhileFakeIoIsBlockedDoesNotRestoreRemovedSlotOrOldDestination(Kind k) throws Exception {
        Long id = fixture(k, false); CapturedExport c = capture(k, id, D1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        fakeWriter(() -> { started.countDown(); await(release); return new DualFormatExportWriter.DualResult(null, null, "成功：old", "Drive：old", null, null); });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> background = executor.submit(() -> scheduled(k, c)); await(started);
            uiSave(k, 9, true, "new-local", "new-drive");
            release.countDown(); background.get(5, TimeUnit.SECONDS);
            Map<String, Object> current = read(k, id);
            assertThat(current.get("outputSubpath")).isEqualTo("new-local");
            assertThat(current.get("gdriveSubpath")).isEqualTo("new-drive");
            assertThat(current.get("childCount")).isEqualTo(1); assertThat(current.get("child.runHour")).isEqualTo(9);
            assertThat(current.get("child.lastRunDate")).isNull(); assertThat(current.get("gdriveLastRunAt")).isNull();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void resultKeepsDateMaximumAndCompletionStatusAtomicEvenWhenOlderResultArrivesLast(Kind k) {
        Long id = fixture(k, false); CapturedExport d1 = capture(k, id, D1), d2 = capture(k, id, D2);
        complete(k, d2, COMPLETED, "new", "drive-new");
        complete(k, d1, COMPLETED.minusHours(1), "old", "drive-old");
        Map<String, Object> current = read(k, id);
        assertThat(current.get("child.lastRunDate")).isEqualTo(D2);
        assertThat(current.get("child.lastRunAt")).isEqualTo(COMPLETED);
        assertThat(current.get("child.lastRunStatus")).isEqualTo("new");
        assertThat(current.get("gdriveLastStatus")).isEqualTo("drive-new");
        if (k != Kind.INDEX) { assertThat(current.get("lastRunAt")).isEqualTo(COMPLETED); assertThat(current.get("lastRunStatus")).isEqualTo("new"); }
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = "INDEX", mode = EnumSource.Mode.EXCLUDE)
    void olderCompletionAdvancesItsChildWithoutChangingNewerParentSummary(Kind k) {
        Long id = fixture(k, false);
        edit(k, p -> {
            set(times(p).get(0), "lastRunDate", D1);
            Object second = newChild(k, p, 9);
            set(second, "lastRunDate", D1);
            times(p).add(second);
        });
        CapturedExport d2 = capture(k, id, D2);
        edit(k, p -> {
            set(p, "runHour", 9); set(p, "runMinute", 0); set(p, "lastRunDate", D1);
            set(p, "lastRunAt", COMPLETED.plusHours(1)); set(p, "lastRunStatus", "newer manual summary");
        });
        complete(k, d2, COMPLETED, "older scheduled result", null);
        Map<String, Object> current = read(k, id);
        assertThat(current.get("childCount")).isEqualTo(2);
        assertThat(current.get("child.lastRunDate")).isEqualTo(D2);
        assertThat(current.get("child.lastRunStatus")).isEqualTo("older scheduled result");
        assertThat(current.get("runHour")).isEqualTo(9);
        assertThat(current.get("lastRunDate")).isEqualTo(D1);
        assertThat(current.get("lastRunAt")).isEqualTo(COMPLETED.plusHours(1));
        assertThat(current.get("lastRunStatus")).isEqualTo("newer manual summary");
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void sameIdDisabledChildStillRecordsResultButNewDriveConfigKeepsItsMetadata(Kind k) {
        Long id = fixture(k, false); CapturedExport c = capture(k, id, D1);
        edit(k, p -> { set(times(p).get(0), "enabled", false); set(p, "gdriveSubpath", "edited-drive"); });
        complete(k, c, COMPLETED, "失敗：local", "drive-old");
        Map<String, Object> current = read(k, id);
        assertThat(current.get("child.lastRunDate")).isEqualTo(D1); assertThat(current.get("child.lastRunStatus")).isEqualTo("失敗：local");
        assertThat(current.get("child.enabled")).isEqualTo(false); assertThat(current.get("gdriveLastRunAt")).isNull();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void removeThenRecreateSameTimeGetsNewIdAndCannotReceiveCapturedResult(Kind k) {
        Long id = fixture(k, false); CapturedExport c = capture(k, id, D1);
        edit(k, p -> times(p).clear());
        edit(k, p -> times(p).add(newChild(k, p, 8)));
        complete(k, c, COMPLETED, "stale", "stale-drive");
        Map<String, Object> current = read(k, id);
        assertThat(current.get("child.id")).isNotEqualTo(c.childId()); assertThat(current.get("child.lastRunDate")).isNull();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void ownerScheduleAndTimeIdentityMustAllMatch(Kind k) {
        Long id = fixture(k, false); CapturedExport c = capture(k, id, D1);
        CapturedExport wrong = new CapturedExport(id, owner, c.childId(), 9, c.minute(), D1, c.outputSubpath(), true, c.gdriveSubpath(), c.rangeMonths(), c.markets());
        complete(k, wrong, COMPLETED, "wrong-time", "wrong-drive");
        wrong = new CapturedExport(id + 100, owner, c.childId(), c.hour(), c.minute(), D1, c.outputSubpath(), true, c.gdriveSubpath(), c.rangeMonths(), c.markets());
        complete(k, wrong, COMPLETED, "wrong-parent", "wrong-drive");
        assertThat(read(k, id).get("child.lastRunDate")).isNull();
        port(k).complete(new CapturedExport(id, owner + 10000, c.childId(), c.hour(), c.minute(), D1, c.outputSubpath(), true, c.gdriveSubpath(), c.rangeMonths(), c.markets()), COMPLETED, "wrong-owner", null);
        assertThat(read(k, id).get("child.lastRunDate")).isNull();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void deletedParentDoesNotReappearAtCompletion(Kind k) {
        Long id = fixture(k, false); CapturedExport c = capture(k, id, D1);
        tx(() -> { em.remove(em.find(k.model, id)); return null; });
        port(k).complete(c, COMPLETED, "stale", "stale-drive"); assertThat(read(k, id)).isEmpty();
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = "INDEX", mode = EnumSource.Mode.EXCLUDE)
    void legacyChildIsPersistedBeforeCaptureAndNeverRecreatedAtCompletion(Kind k) {
        Long id = fixture(k, true); CapturedExport c = capture(k, id, D1);
        assertThat(c.childId()).isNotNull(); assertThat(read(k, id).get("child.id")).isEqualTo(c.childId());
        edit(k, p -> times(p).clear()); complete(k, c, COMPLETED, "old", null);
        assertThat(read(k, id).get("childCount")).isEqualTo(0);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void manualExportSuspendsCallerTransactionAndDoesNotTouchChildDate(Kind k) {
        Long id = fixture(k, false);
        assertThat(AopUtils.isAopProxy(service(k))).isTrue(); assertThat(AopUtils.isAopProxy(port(k))).isTrue();
        tx(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            ReflectionTestUtils.invokeMethod(service(k), "runNowForCurrentUser");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return null;
        });
        Map<String, Object> current = read(k, id);
        assertThat(current.get("child.lastRunDate")).isNull(); assertThat(current.get("child.lastRunAt")).isNull();
        assertThat(current.get("gdriveLastStatus")).isEqualTo("失敗：synthetic Drive");
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void manualDefaultNeverCreatesSettingsWhenNoSettingsWereSaved(Kind k) {
        ReflectionTestUtils.invokeMethod(service(k), "runNowForCurrentUser");
        assertThat(port(k).manual(owner).scheduleId()).isNull();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void manualCaptureThenConcurrentSaveUsesFreshOsivReadAtCompletion(Kind k) {
        Long id = fixture(k, false); EntityManager osiv = emf.createEntityManager();
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(osiv));
        try {
            CapturedExport c = port(k).manual(owner);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try { executor.submit(() -> uiSave(k, 9, true, "osiv-new", "osiv-drive")).get(5, TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError(e); } finally { executor.shutdownNow(); }
            port(k).complete(c, COMPLETED, "manual", "stale-drive");
        } finally { TransactionSynchronizationManager.unbindResource(emf); osiv.close(); }
        Map<String, Object> current = read(k, id);
        assertThat(current.get("outputSubpath")).isEqualTo("osiv-new"); assertThat(current.get("gdriveSubpath")).isEqualTo("osiv-drive");
        assertThat(current.get("child.runHour")).isEqualTo(9); assertThat(current.get("child.lastRunDate")).isNull();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void uiUpdateHoldingParentLockThenCompletionWaitsAndPreservesBothSettingsAndResult(Kind k) throws Exception {
        Long id = fixture(k, false); CapturedExport c = capture(k, id, D1);
        CountDownLatch uiLocked = new CountDownLatch(1), release = new CountDownLatch(1);
        when(drive.resolveUpdate(anyLong(), nullable(Boolean.class), nullable(String.class), anyBoolean(), nullable(String.class)))
            .thenAnswer(i -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); uiLocked.countDown(); await(release);
                return new GdriveOutputSupport.DriveSettings(false, "reverse-drive", null); });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> ui = executor.submit(() -> uiSave(k, 8, true, "reverse-local", "reverse-drive")); await(uiLocked);
            CountDownLatch outcomeStarted = new CountDownLatch(1);
            Future<?> outcome = executor.submit(() -> { outcomeStarted.countDown(); port(k).complete(c, COMPLETED, "after-ui", "old-drive"); });
            await(outcomeStarted); assertThatThrownBy(() -> outcome.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); ui.get(5, TimeUnit.SECONDS); outcome.get(5, TimeUnit.SECONDS);
            Map<String, Object> current = read(k, id);
            assertThat(current.get("outputSubpath")).isEqualTo("reverse-local"); assertThat(current.get("gdriveSubpath")).isEqualTo("reverse-drive");
            assertThat(current.get("child.lastRunDate")).isEqualTo(D1); assertThat(current.get("child.lastRunStatus")).isEqualTo("after-ui");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void syntheticFileFailureStillSetsGuardAndSkippedDriveStatus(Kind k) throws Exception {
        Long id = fixture(k, false); CapturedExport c = capture(k, id, D1);
        when(writer.write(anyLong(), any(Path.class), anyString(), nullable(byte[].class), nullable(byte[].class), anyBoolean(), nullable(String.class)))
                .thenAnswer(i -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); throw new IOException("synthetic file failure"); });
        scheduled(k, c); Map<String, Object> current = read(k, id);
        assertThat(current.get("child.lastRunDate")).isEqualTo(D1); assertThat(current.get("child.lastRunStatus").toString()).contains("synthetic file failure");
        assertThat(current.get("gdriveLastStatus")).isEqualTo("跳過：本輪未產生本機檔案");
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void deniedInitialUiSaveRollsBackItsNewUniqueOwnerSetting(Kind k) {
        when(drive.resolveUpdate(anyLong(), nullable(Boolean.class), nullable(String.class), anyBoolean(), nullable(String.class)))
                .thenThrow(new AdminRequiredException("synthetic denied"));
        assertThatThrownBy(() -> uiSave(k, 8, true, "local", "remote")).isInstanceOf(AdminRequiredException.class);
        assertThat(port(k).manual(owner).scheduleId()).isNull();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void syntheticManualFileFailureUpdatesSkippedStatusWithoutChangingDateGuard(Kind k) throws Exception {
        Long id = fixture(k, false);
        when(writer.write(anyLong(), any(Path.class), anyString(), nullable(byte[].class), nullable(byte[].class), anyBoolean(), nullable(String.class)))
                .thenAnswer(i -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); throw new IOException("synthetic manual failure"); });
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service(k), "runNowForCurrentUser"))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("synthetic manual failure");
        Map<String, Object> current = read(k, id);
        assertThat(current.get("child.lastRunDate")).isNull(); assertThat(current.get("child.lastRunAt")).isNull();
        assertThat(current.get("gdriveLastStatus")).isEqualTo("跳過：本輪未產生本機檔案");
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void simultaneousInitialUiSavesRetryUniqueOwnerInsertAfterLosingTransactionRollsBack(Kind k) throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        when(drive.resolveUpdate(anyLong(), nullable(Boolean.class), nullable(String.class), anyBoolean(), nullable(String.class)))
                .thenAnswer(i -> {
                    if (callbacks.incrementAndGet() == 1) { firstInserted.countDown(); await(release); }
                    return new GdriveOutputSupport.DriveSettings(false, i.getArgument(2), null);
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> uiSave(k, 8, true, "first-insert", "first-drive")); await(firstInserted);
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = executor.submit(() -> { secondStarted.countDown(); uiSave(k, 9, true, "second-insert", "second-drive"); });
            await(secondStarted); assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            CapturedExport c = port(k).manual(owner);
            assertThat(read(k, c.scheduleId()).get("outputSubpath")).isEqualTo("second-insert");
            long count = tx(() -> em.createQuery("select count(s) from " + k.model.getSimpleName() + " s where s.ownerUserId = :owner", Long.class)
                    .setParameter("owner", owner).getSingleResult());
            assertThat(count).isEqualTo(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void childUpdateTimeNeverMovesBackwardAndResultStatusesKeepTheirColumnLimits(Kind k) {
        Long id = fixture(k, false); CapturedExport c = capture(k, id, D1);
        edit(k, p -> set(times(p).get(0), "updatedAt", COMPLETED.plusHours(1)));
        complete(k, c, COMPLETED, "x".repeat(900), "y".repeat(900));
        Map<String, Object> current = read(k, id);
        assertThat(current.get("child.updatedAt")).isEqualTo(COMPLETED.plusHours(1));
        assertThat(current.get("child.lastRunStatus").toString()).hasSize(500).endsWith("…");
        assertThat(current.get("gdriveLastStatus").toString()).hasSize(512).endsWith("…");
    }

}
