package com.steven.assets.repository;

import com.steven.assets.model.StockDividendHistory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Task 557: execute the real Spring Data query on PostgreSQL; HQL parsing cannot catch overload ambiguity. */
@Testcontainers
class StockDividendHistoryAdjustmentPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static SessionFactory factory;
    static final LocalDate FROM = LocalDate.of(2025, 12, 31);
    static final LocalDate TO = LocalDate.of(2026, 1, 2);

    @BeforeAll
    static void setup() {
        factory = new Configuration().addAnnotatedClass(StockDividendHistory.class)
                .setProperty("hibernate.connection.url", POSTGRES.getJdbcUrl())
                .setProperty("hibernate.connection.username", POSTGRES.getUsername())
                .setProperty("hibernate.connection.password", POSTGRES.getPassword())
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterAll
    static void close() { if (factory != null) factory.close(); }

    @Test
    void originalUntypedYearFailsOnPostgresRatherThanMerelyParsingSuccessfully() throws Exception {
        String repaired = StockDividendHistoryRepository.class
                .getMethod("findAdjustmentEvents", String.class, String.class, LocalDate.class, LocalDate.class)
                .getAnnotation(Query.class).value();
        String original = repaired.replace("cast(:fromDate as LocalDate)", ":fromDate")
                .replace("cast(:toDate as LocalDate)", ":toDate");
        try (Session session = factory.openSession()) {
            var transaction = session.beginTransaction();
            try {
                Throwable failure = catchThrowable(() -> session.createQuery(original, StockDividendHistory.class)
                        .setParameter("code", "FIXTURE").setParameter("market", "台股")
                        .setParameter("fromDate", FROM).setParameter("toDate", TO).getResultList());
                assertThat(failure).isNotNull();
                Throwable cause = failure;
                while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
                assertThat(cause).isInstanceOf(SQLException.class);
                assertThat(((SQLException) cause).getSQLState()).isEqualTo("42725");
            } finally { transaction.rollback(); }
        }
    }

    @Test
    void realRepositoryPreservesDateAndYearFallbackBoundariesAndLeavesTransactionUsable() {
        try (Session session = factory.openSession()) {
            var transaction = session.beginTransaction();
            try {
                var first = event(2025, null, FROM, null, "FIXTURE", "台股", "ACTIVE");
                var last = event(2026, null, TO, null, "FIXTURE", "台股", "ACTIVE");
                var rightsOnly = event(2026, null, null, "2026-01-01", "FIXTURE", "台股", "ACTIVE");
                var earlierRights = event(2026, null, LocalDate.parse("2026-02-01"), "2026-01-01", "FIXTURE", "台股", "ACTIVE");
                var canonical = event(2026, "2026-01-01", LocalDate.parse("2026-03-01"), null, "FIXTURE", "台股", "ACTIVE");
                var undated2025 = event(2025, null, null, null, "FIXTURE", "台股", "ACTIVE");
                var undated2026 = event(2026, null, null, null, "FIXTURE", "台股", "ACTIVE");
                List<StockDividendHistory> included = List.of(first, last, rightsOnly, earlierRights, canonical, undated2025, undated2026);
                included.forEach(session::persist);
                // Canonical date wins even if the legacy anchor lies inside the window.
                session.persist(event(2026, "2026-03-01", TO, null, "FIXTURE", "台股", "ACTIVE"));
                session.persist(event(2025, null, FROM.minusDays(1), null, "FIXTURE", "台股", "ACTIVE"));
                session.persist(event(2026, null, TO.plusDays(1), null, "FIXTURE", "台股", "ACTIVE"));
                session.persist(event(2024, null, null, null, "FIXTURE", "台股", "ACTIVE"));
                session.persist(event(2027, null, null, null, "FIXTURE", "台股", "ACTIVE"));
                session.persist(event(2026, null, TO, null, "FIXTURE", "台股", "CANCELLED"));
                session.persist(event(2026, null, TO, null, "OTHER", "台股", "ACTIVE"));
                session.persist(event(2026, null, TO, null, "FIXTURE", "美股", "ACTIVE"));
                session.flush();
                session.clear();

                var repository = new JpaRepositoryFactory(session).getRepository(StockDividendHistoryRepository.class);
                var result = repository.findAdjustmentEvents("FIXTURE", "台股", FROM, TO);
                assertThat(result).extracting(StockDividendHistory::getId)
                        .containsExactly(first.getId(), rightsOnly.getId(), earlierRights.getId(), last.getId(),
                                canonical.getId(), undated2025.getId(), undated2026.getId());
                // Missing amounts survive for evidence gating; they must not become a false "no event".
                assertThat(result).allSatisfy(row -> {
                    assertThat(row.getCashDividend()).isNull();
                    assertThat(row.getStockDividend()).isNull();
                });
                assertThat(repository.findAdjustmentEvents("FIXTURE", "台股", FROM, FROM))
                        .extracting(StockDividendHistory::getId).containsExactly(first.getId(), undated2025.getId());
                assertThat(session.createNativeQuery("select 1", Integer.class).getSingleResult()).isEqualTo(1);
            } finally { transaction.rollback(); }
        }
    }

    private static StockDividendHistory event(int year, String canonical, LocalDate cash, String rights,
                                               String code, String market, String status) {
        return StockDividendHistory.builder().stockCode(code).market(market).year(year)
                .canonicalDate(canonical == null ? null : LocalDate.parse(canonical))
                .exDividendDate(cash).exRightsDate(rights == null ? null : LocalDate.parse(rights))
                .eventStatus(status).resolutionStatus("UNRESOLVED")
                .updatedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
    }
}
