package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.SrppEventEvidenceBundle;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.SrppEventEvidenceBundleRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.srpp.SrppFormulaCatalog;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Requirement 181／Task 481.9／481.13：以真實 PostgreSQL（Testcontainers，沿用 {@code SrppRepositoriesPostgresTest}
 * 的設定）套用 v1.147.0 changeset（兩次，驗證冪等），驗證具名 unique 識別、CHECK、外鍵、{@code BEFORE UPDATE}
 * trigger、佔位表已刪除，以及 20 執行緒並行送同一請求只產生一列且所有成功回應的 {@code eventBundleContentSha256} 相同。
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(SrppPolicyRegistryService.class)
class SrppEventEvidenceBundleRepositoryPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("srpp_event_evidence_test")
            .withUsername("assets")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final Instant NOW = Instant.parse("2026-10-12T01:10:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-10-12");
    private static final String POLICY = "a".repeat(64);
    private static final String SWAGGER = "b".repeat(64);
    private static final String IDENTITY = SrppEventEvidenceBundle.IDENTITY_CONSTRAINT;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SrppEventEvidenceBundleRepository bundles;
    @Autowired NewsHeadlineRepository news;
    @Autowired SrppPolicyRegistryService registry;
    @Autowired EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void schema() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_user (id bigint PRIMARY KEY)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS news_headline (id bigserial PRIMARY KEY, title varchar(500) NOT NULL, "
                + "source varchar(100) NOT NULL, url varchar(1024) NOT NULL, category varchar(32) NOT NULL, region varchar(16), "
                + "summary text, published_at timestamptz NOT NULL, fetched_at timestamptz NOT NULL DEFAULT now(), "
                + "dedupe_key varchar(64) NOT NULL)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_news_headline_dedupe ON news_headline (dedupe_key)");
        jdbc.execute(changeset("v1.123.0-api-error-log.sql"));
        jdbc.execute(changeset("v1.136.0-srpp-daily-context.sql"));
        jdbc.execute(changeset("v1.144.0-srpp-event-evidence.sql"));        // 佔位表（t483 之前）
        String bundleChangeset = changeset("v1.147.0-srpp-event-evidence-bundle.sql");
        jdbc.execute(bundleChangeset);
        jdbc.execute(bundleChangeset);                                       // 冪等
        jdbc.execute("DELETE FROM srpp_event_evidence_bundle");
        jdbc.execute("DELETE FROM srpp_context_package");
        jdbc.execute("DELETE FROM srpp_policy_registry");
        jdbc.execute("DELETE FROM news_headline");
        jdbc.execute("INSERT INTO app_user (id) VALUES (7), (8) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO srpp_policy_registry (policy_bundle_sha256, formula_version, policy_document, "
                        + "formula_manifest) VALUES (?, ?, ?, ?)", POLICY, SrppFormulaCatalog.FORMULA_VERSION,
                "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{}}", SrppFormulaCatalog.manifestJcs());
    }

    private static String changeset(String name) throws Exception {
        return new ClassPathResource("db/changelog/changes/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private void tx(Runnable body) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> body.run());
    }

    private static SrppEventEvidenceBundle bundle(long owner, String consumer, String decisionId, String policy) {
        return new SrppEventEvidenceBundle(UUID.randomUUID(), owner, DATE, "09:05", "TW_DAILY", consumer, decisionId,
                policy, SWAGGER, "c".repeat(64), "{}", NOW);
    }

    private static PSQLException psql(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException exception) return exception;
        }
        throw new AssertionError("cause chain 沒有 PSQLException", failure);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM srpp_event_evidence_bundle", Integer.class);
    }

    @Test
    void changesetCreatesBundleTableWithTriggerAndDropsPlaceholder() {
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.srpp_event_evidence')::text", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.srpp_event_evidence_bundle')::text", String.class))
                .isEqualTo("srpp_event_evidence_bundle");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname = "
                + "'trg_srpp_event_evidence_bundle_no_update' AND NOT tgisinternal", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = ? AND contype = 'u'",
                Integer.class, IDENTITY)).isEqualTo(1);
    }

    @Test
    void identityIsUniqueAndLoserIsRecognisedOnlyBySqlState23505AndNamedConstraint() {
        tx(() -> bundles.saveAndFlush(bundle(7L, "Claude", "D1", POLICY)));

        Throwable duplicate = catchThrowable(() -> tx(() -> bundles.saveAndFlush(bundle(7L, "Claude", "D1", POLICY))));

        PSQLException exception = psql(duplicate);
        assertThat(exception.getSQLState()).isEqualTo("23505");
        assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo(IDENTITY);
        assertThat(EventEvidenceCaptureService.isIdentityConflict(duplicate)).isTrue();
        assertThat(rows()).isEqualTo(1);
        assertThat(bundles.findIdentity(7L, DATE, "09:05", "TW_DAILY", "Claude", "D1")).isPresent();
        assertThat(bundles.findIdentity(8L, DATE, "09:05", "TW_DAILY", "Claude", "D1")).isEmpty();
    }

    @Test
    void differentConsumerDecisionOrOwnerAreIndependentBundles() {
        tx(() -> {
            bundles.saveAndFlush(bundle(7L, "Claude", "D1", POLICY));
            bundles.saveAndFlush(bundle(7L, "Codex", "D1", POLICY));
            bundles.saveAndFlush(bundle(7L, "Claude", "D2", POLICY));
            bundles.saveAndFlush(bundle(8L, "Claude", "D1", POLICY));
        });
        assertThat(rows()).isEqualTo(4);
    }

    @Test
    void foreignKeysAreEnforcedAndAreNotIdentityConflicts() {
        Throwable owner = catchThrowable(() -> tx(() -> bundles.saveAndFlush(bundle(999L, "Claude", "D1", POLICY))));
        assertThat(psql(owner).getSQLState()).isEqualTo("23503");
        assertThat(EventEvidenceCaptureService.isIdentityConflict(owner)).isFalse();

        Throwable policy = catchThrowable(() -> tx(() -> bundles.saveAndFlush(bundle(7L, "Claude", "D1", "f".repeat(64)))));
        assertThat(psql(policy).getSQLState()).isEqualTo("23503");
        assertThat(EventEvidenceCaptureService.isIdentityConflict(policy)).isFalse();

        tx(() -> bundles.saveAndFlush(bundle(7L, "Claude", "D1", POLICY)));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM srpp_policy_registry"))
                .hasMessageContaining("srpp_event_evidence_bundle");
        assertThat(rows()).isEqualTo(1);
    }

    private void insertRaw(String slot, String profile, String consumer) {
        jdbc.update("INSERT INTO srpp_event_evidence_bundle (id, owner_user_id, trading_date, slot, analysis_profile, consumer, "
                        + "decision_id, policy_bundle_sha256, swagger_sha256, request_sha256, content_jcs, created_at) "
                        + "VALUES (?, 7, ?, ?, ?, ?, 'D1', ?, ?, ?, '{}', now())",
                UUID.randomUUID(), DATE, slot, profile, consumer, POLICY, SWAGGER, "c".repeat(64));
    }

    @Test
    void checkConstraintsFixProtocolEnumerations() {
        assertThatThrownBy(() -> insertRaw("10:00", "TW_DAILY", "Claude")).hasMessageContaining("srpp_event_evidence_bundle_slot_check");
        assertThatThrownBy(() -> insertRaw("09:05", "TW_WEEKLY", "Claude"))
                .hasMessageContaining("srpp_event_evidence_bundle_analysis_profile_check");
        assertThatThrownBy(() -> insertRaw("09:05", "TW_DAILY", "Gemini")).hasMessageContaining("srpp_event_evidence_bundle_consumer_check");
        insertRaw("11:40", "TW_DAILY", "Codex");
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void updateIsRejectedByTriggerButDeleteIsAllowed() {
        tx(() -> bundles.saveAndFlush(bundle(7L, "Claude", "D1", POLICY)));

        assertThatThrownBy(() -> jdbc.update("UPDATE srpp_event_evidence_bundle SET content_jcs = '{\"x\":1}'"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE srpp_event_evidence_bundle SET decision_id = 'D2'"))
                .hasMessageContaining("immutable");
        assertThat(jdbc.queryForObject("SELECT content_jcs FROM srpp_event_evidence_bundle", String.class)).isEqualTo("{}");
        assertThat(jdbc.update("DELETE FROM srpp_event_evidence_bundle WHERE decision_id = 'D1'")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 20 執行緒並行

    private EventEvidenceCaptureService service() {
        CurrentUserContext owner = new CurrentUserContext();
        owner.setEffectiveUserId(7L);
        owner.setRole("ADMIN");
        owner.setStatus("ACTIVE");
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(true));
        PublishedSwaggerIdentity swagger = mock(PublishedSwaggerIdentity.class);
        when(swagger.sha256()).thenReturn(Optional.of(SWAGGER));
        EventEvidenceCaptureService.Settings settings = new EventEvidenceCaptureService.Settings(
                Set.of("fx", "us-market", "kr-market", "kr-intraday"), Set.of("/api/public/commodity-prices"), 31,
                List.of("federalreserve.gov"));
        return new EventEvidenceCaptureService(owner, marketData, registry, swagger, bundles, news,
                EventEvidenceCaptureService.newTransactions(transactionManager), settings, Clock.fixed(NOW, TW));
    }

    private static String request() {
        return "{\"tradingDate\":\"2026-10-12\",\"slot\":\"09:05\",\"consumer\":\"Codex\",\"decisionId\":\"CODEX-20261012-0905\","
                + "\"policyBundleSha256\":\"" + POLICY + "\",\"swaggerSha256\":\"" + SWAGGER + "\",\"categories\":["
                + "{\"code\":\"fed\",\"claimedScore\":1,\"sources\":[{\"kind\":\"NEWS_HEADLINE\",\"source\":\"ltn\","
                + "\"url\":\"https://news.ltn.com.tw/news/business/breakingnews/1\",\"category\":\"news\","
                + "\"title\":\"臺股 開高\",\"publishedAt\":\"2026-10-12T08:30:00+08:00\"}]},"
                + "{\"code\":\"geopolitics\",\"claimedScore\":0,\"sources\":[{\"kind\":\"API_RESPONSE\","
                + "\"endpoint\":\"/api/public/commodity-prices\",\"observedDate\":\"2026-10-09\"}]},"
                + "{\"code\":\"oil\",\"claimedScore\":0,\"sources\":[]},"
                + "{\"code\":\"taiwan_politics\",\"claimedScore\":0,\"sources\":[]},"
                + "{\"code\":\"us_taiwan_inflation\",\"claimedScore\":0,\"sources\":[]},"
                + "{\"code\":\"semiconductor_cycle_and_advanced_process\",\"claimedScore\":0,\"sources\":[]}]}";
    }

    /**
     * {@code openEntityManagerInView=true} 模擬正式環境的 OSIV：每個執行緒先綁定自己的 EntityManager，兩次
     * {@code template.execute} 共用它（輸家的第一個 transaction rollback 後由 JpaTransactionManager clear，再於第二個
     * transaction 重讀）。
     */
    @ParameterizedTest(name = "openEntityManagerInView={0}")
    @ValueSource(booleans = {false, true})
    void twentyConcurrentIdenticalRequestsProduceOneRowAndOneContentHash(boolean openEntityManagerInView) throws Exception {
        String key = EventEvidenceCaptureService.dedupeKey("ltn", "https://news.ltn.com.tw/news/business/breakingnews/1", "news");
        jdbc.update("INSERT INTO news_headline (title, source, url, category, region, summary, published_at, fetched_at, dedupe_key) "
                        + "VALUES ('台股 開高', 'ltn', 'https://news.ltn.com.tw/news/business/breakingnews/1', 'news', 'TW', '摘要', ?, ?, ?)",
                Timestamp.from(Instant.parse("2026-10-12T00:30:00.654321Z")), Timestamp.from(Instant.parse("2026-10-12T00:31:00Z")), key);
        EventEvidenceCaptureService service = service();
        String raw = request();

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EventEvidenceCaptureService.Result>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<EventEvidenceCaptureService.Result>) () -> {
                    EntityManager bound = openEntityManagerInView ? entityManagerFactory.createEntityManager() : null;
                    if (bound != null) TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(bound));
                    try {
                        ready.countDown();
                        start.await();
                        return service.capture(raw);
                    } finally {
                        if (bound != null) {
                            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
                            bound.close();
                        }
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<EventEvidenceCaptureService.Result> results = new ArrayList<>();
            for (Future<EventEvidenceCaptureService.Result> future : futures) results.add(future.get(60, TimeUnit.SECONDS));

            assertThat(results).hasSize(threads);
            assertThat(results.stream().filter(r -> r.status() == HttpStatus.CREATED)).hasSize(1);
            assertThat(results.stream().filter(r -> r.status() == HttpStatus.OK)).hasSize(threads - 1);
            assertThat(results.stream().map(EventEvidenceCaptureService.Result::eventBundleContentSha256).distinct()).hasSize(1);
            assertThat(results.stream().map(EventEvidenceCaptureService.Result::eventBundleId).distinct()).hasSize(1);
            assertThat(rows()).isEqualTo(1);

            JsonNode body = JSON.readTree(results.get(0).body());
            assertThat(body.path("riskAssessment").path("totalScore").intValue()).isEqualTo(1);
            assertThat(body.path("riskAssessment").path("assessedCount").intValue()).isEqualTo(2);
            assertThat(body.path("riskAssessment").path("riskMode").asText()).isEqualTo("DEFENSIVE");
            assertThat(body.path("riskAssessment").path("categories").get(0).path("sourceReceipts").get(0)
                    .path("period").asText()).isEqualTo("2026-10-12T08:30:00+08:00");
            String stored = jdbc.queryForObject("SELECT content_jcs FROM srpp_event_evidence_bundle", String.class);
            assertThat(stored).contains(results.get(0).eventBundleContentSha256()).doesNotContain("idempotentReplay");
        } finally {
            pool.shutdownNow();
        }
    }
}
