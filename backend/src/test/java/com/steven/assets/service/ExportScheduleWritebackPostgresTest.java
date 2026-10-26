package com.steven.assets.service;

import com.steven.assets.dto.*;
import com.steven.assets.model.*;
import com.steven.assets.repository.ExportScheduleFreshRead;
import com.steven.assets.repository.IndexExportScheduleRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

/** Existing index PostgreSQL coverage. All four migrated schedule families are tested in IndependentAssetGainExportPostgresTest. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ExportScheduleFreshRead.class, IndexExportScheduleExecutionStore.class,
        IndexExportScheduleService.class, ExportScheduleWritebackPostgresTest.Config.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExportScheduleWritebackPostgresTest {
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
    @Autowired IndexExportScheduleExecutionStore index;
    @Autowired IndexExportScheduleRepository indexRepository;
    @Autowired IndexExportScheduleService indexService;
    @MockitoBean ExcelExportService excel;
    @MockitoBean ExcelDocRenderer xlsx;
    @MockitoBean JsonDocRenderer json;
    @MockitoBean DualFormatExportWriter writer;
    @MockitoBean GdriveOutputSupport drive;
    @MockitoBean CurrentUserContext user;
    private static final AtomicLong OWNERS = new AtomicLong(1000);
    private static final LocalDate D1 = LocalDate.of(2026, 9, 15);
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
    private <T> T tx(Supplier<T> body) { return new TransactionTemplate(tm).execute(s -> body.get()); }
    private void fakeWriter(Supplier<DualFormatExportWriter.DualResult> outcome) throws IOException {
        when(writer.write(anyLong(), any(Path.class), anyString(), nullable(byte[].class), nullable(byte[].class), anyBoolean(), nullable(String.class)))
                .thenAnswer(i -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return outcome.get(); });
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private IndexExportDto.ScheduleRequest indexRequest(String name, String outputSubpath) {
        return new IndexExportDto.ScheduleRequest(name, false, 8, 15, List.of("TWSE"), 6,
                outputSubpath, false, null);
    }

    private void seedIndexSchedules(int count) {
        tx(() -> {
            for (int i = 0; i < count; i++) {
                IndexExportSchedule schedule = IndexExportSchedule.builder()
                        .ownerUserId(owner).name("fixture_" + i).enabled(false).runHour(8).runMinute(i)
                        .markets(new LinkedHashSet<>(Set.of("TWSE"))).rangeMonths(6)
                        .outputSubpath("fixture-" + i).build();
                em.persist(schedule);
            }
            em.flush();
            return null;
        });
    }

    private Long seedDueIndexSchedule(String market) {
        return tx(() -> {
            IndexExportSchedule schedule = IndexExportSchedule.builder()
                    .ownerUserId(owner).name("due-" + market).enabled(true).runHour(0).runMinute(0)
                    .lastRunDate(D1).markets(new LinkedHashSet<>(Set.of(market))).rangeMonths(6)
                    .outputSubpath("due-" + market).gdriveEnabled(false).build();
            em.persist(schedule);
            em.flush();
            return schedule.getId();
        });
    }

    private Long seedIndexScheduleForOwner(Long scheduleOwner) {
        return tx(() -> {
            IndexExportSchedule schedule = IndexExportSchedule.builder()
                    .ownerUserId(scheduleOwner).name("foreign-owner").enabled(false).runHour(8).runMinute(0)
                    .markets(new LinkedHashSet<>(Set.of("TWSE"))).rangeMonths(6)
                    .outputSubpath("foreign-owner").build();
            em.persist(schedule);
            em.flush();
            return schedule.getId();
        });
    }

    private Map<String, Object> readIndexSchedule(Long id) {
        return tx(() -> {
            em.clear();
            IndexExportSchedule schedule = indexRepository.findByIdAndOwnerUserId(id, owner).orElseThrow();
            return Map.of("lastRunDate", schedule.getLastRunDate(),
                    "lastRunAt", schedule.getLastRunAt(), "lastRunStatus", schedule.getLastRunStatus());
        });
    }

    private Object concurrentCreate(CountDownLatch ready, CountDownLatch start,
                                    IndexExportDto.ScheduleRequest request) {
        ready.countDown();
        await(start);
        try {
            indexService.createForCurrentUser(request);
            return "created";
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    @Test
    void ownerAdvisoryLockSerializesTwoCreatesCompetingForTenthSlot() throws Exception {
        seedIndexSchedules(9);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = executor.submit(() -> concurrentCreate(ready, start, indexRequest("last_a", "last-a")));
            Future<Object> b = executor.submit(() -> concurrentCreate(ready, start, indexRequest("last_b", "last-b")));
            await(ready);
            start.countDown();
            List<Object> outcomes = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("created"::equals)).hasSize(1);
            assertThat(outcomes.stream().filter(IllegalArgumentException.class::isInstance)).hasSize(1);
            assertThat(tx(() -> indexRepository.countByOwnerUserId(owner))).isEqualTo(10);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void ownerAdvisoryLockSerializesSameConflictKeyFromZeroExistingRows() throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            IndexExportDto.ScheduleRequest same = indexRequest("same", "same-folder");
            Future<Object> a = executor.submit(() -> concurrentCreate(ready, start, same));
            Future<Object> b = executor.submit(() -> concurrentCreate(ready, start, same));
            await(ready);
            start.countDown();
            List<Object> outcomes = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("created"::equals)).hasSize(1);
            assertThat(outcomes.stream().filter(IllegalArgumentException.class::isInstance)).hasSize(1);
            assertThat(tx(() -> indexRepository.countByOwnerUserId(owner))).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void foreignOwnedIndexScheduleIsNotVisibleForUpdateDeleteOrRunNow() {
        Long foreignOwner = owner + 1_000_000;
        Long foreignScheduleId = seedIndexScheduleForOwner(foreignOwner);

        assertThat(tx(() -> indexRepository.findByIdAndOwnerUserId(foreignScheduleId, foreignOwner))).isPresent();
        assertThat(tx(() -> indexRepository.findByIdAndOwnerUserId(foreignScheduleId, owner))).isEmpty();
        assertThat(tx(() -> indexRepository.findLockedByIdAndOwnerUserId(foreignScheduleId, owner))).isEmpty();
        assertThat(tx(() -> indexRepository.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(owner)))
                .noneMatch(schedule -> schedule.getId().equals(foreignScheduleId));

        assertThatThrownBy(() -> indexService.updateForCurrentUser(foreignScheduleId,
                indexRequest("attacker-edit", "attacker-edit")))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> indexService.deleteForCurrentUser(foreignScheduleId))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> indexService.runNowForCurrentUser(foreignScheduleId))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void dueIndexRowsRunIndependentlyAndFailureStillAdvancesItsDateGuard() throws Exception {
        Long successfulId = seedDueIndexSchedule("SPX");
        Long failedId = seedDueIndexSchedule("TWSE");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        fakeWriter(() -> new DualFormatExportWriter.DualResult(
                Path.of("/synthetic/index.json"), Path.of("/synthetic/index.xlsx"),
                "成功：synthetic", null, null, null));
        doThrow(new IllegalStateException("synthetic TWSE failure"))
                .when(excel).indexDailyDoc(eq("TWSE"), any(), any());

        indexService.tick();

        Map<String, Object> successful = readIndexSchedule(successfulId);
        Map<String, Object> failed = readIndexSchedule(failedId);
        assertThat(successful.get("lastRunDate")).isEqualTo(today);
        assertThat(successful.get("lastRunAt")).isNotNull();
        assertThat(successful.get("lastRunStatus").toString()).contains("SPX 成功");
        assertThat(failed.get("lastRunDate")).isEqualTo(today);
        assertThat(failed.get("lastRunAt")).isNotNull();
        assertThat(failed.get("lastRunStatus").toString()).contains("TWSE 失敗", "synthetic TWSE failure");
        verify(excel, org.mockito.Mockito.times(1)).indexDailyDoc(eq("SPX"), any(), any());
        verify(excel, org.mockito.Mockito.times(1)).indexDailyDoc(eq("TWSE"), any(), any());
        assertThat(index.due(successfulId, owner, today, LocalTime.MAX)).isEmpty();
        assertThat(index.due(failedId, owner, today, LocalTime.MAX)).isEmpty();
    }

}
