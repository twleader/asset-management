package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.PriceQueryService;
import com.steven.assets.service.StockPriceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Requirement 163／Task 453.5：讀取端每個錯誤碼分支、判斷順序、freshness 與零副作用。 */
class SrppDailyContextReadServiceTest {
    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final LocalDate DATE = SrppTestData.DATE;
    private static final String HASH = "a".repeat(64);
    private static final String PKG = SrppContextInvariantsTest.PACKAGE.toString();
    private static final long OWNER = 7L;

    private final CurrentUserContext user = new CurrentUserContext();
    private final SrppPolicyRegistryService registry = mock(SrppPolicyRegistryService.class);
    private final SrppContextPackageRepository packages = mock(SrppContextPackageRepository.class);
    private final SrppContextEvidenceRepository evidence = mock(SrppContextEvidenceRepository.class);
    private final AssetSnapshotRepository snapshots = mock(AssetSnapshotRepository.class);
    private final AssetService assets = mock(AssetService.class);
    private final MarketDataService marketData = mock(MarketDataService.class);
    // 不屬於讀取服務依賴的元件：以 mock 存在並證明零互動。
    private final StockPriceService stockPrices = mock(StockPriceService.class);
    private final PriceQueryService priceQuery = mock(PriceQueryService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SrppPackagePublisher publisher = mock(SrppPackagePublisher.class);
    private final SrppSourceCapture capture = mock(SrppSourceCapture.class);

    private final AssetSnapshot entity = new AssetSnapshot();
    private SrppContextPackage pkg;

    @BeforeEach
    void setUp() {
        user.setEffectiveUserId(OWNER);
        user.setRole("ADMIN");
        user.setStatus("ACTIVE");
        ObjectNode context = SrppContextInvariantsTest.context();
        pkg = new SrppContextPackage(SrppContextInvariantsTest.PACKAGE, OWNER, DATE, "09:05", HASH,
                Instant.parse("2026-09-24T01:05:01Z"), SrppJcs.canonicalize(context));
        when(registry.find(HASH)).thenReturn(Optional.of(SrppTestData.policy(Map.of())));
        when(packages.findLatestForOwner(OWNER, DATE, "09:05", HASH)).thenReturn(Optional.of(pkg));
        when(packages.findByPackageIdAndOwner(SrppContextInvariantsTest.PACKAGE, OWNER)).thenReturn(Optional.of(pkg));
        when(snapshots.findLatestWithStocksByOwnerUserId(OWNER)).thenReturn(Optional.of(entity));
        when(assets.getSnapshotDetail(entity)).thenReturn(SrppTestData.proposal().snapshot());
        when(marketData.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(true));
        when(evidence.findBody(SrppContextInvariantsTest.PACKAGE, "assets")).thenReturn(Optional.of("{\"a\":\"台股\\n\"}"));
    }

    @AfterEach
    void zeroSideEffects() {
        verifyNoInteractions(stockPrices, priceQuery, redis, publisher, capture);
        verify(packages, never()).insertPackage(any(), anyLong(), any(), any(), any(), any(), any());
        verify(packages, never()).deleteTradingDateBefore(any());
        verify(packages, never()).save(any());
        verify(packages, never()).delete(any());
        verify(evidence, never()).insertEvidence(any(), any(), any());
        verify(evidence, never()).save(any());
        verify(snapshots, never()).save(any());
        verify(snapshots, never()).findLatestWithStocks();
        verify(marketData, org.mockito.Mockito.atLeast(0)).isTwTradingDayCachedOnly(any());
        verifyNoMoreInteractions(marketData);
    }

    private SrppDailyContextReadService service(String time) {
        return service(DATE, time);
    }

    private SrppDailyContextReadService service(LocalDate date, String time) {
        Clock clock = Clock.fixed(LocalDateTime.of(date, LocalTime.parse(time)).atZone(TW).toInstant(), TW);
        return new SrppDailyContextReadService(user, registry, packages, evidence, snapshots, assets, marketData,
                new ObjectMapper(), clock);
    }

    private static String code(SrppReadResult result) {
        return result.code();
    }

    // ───── 400 ─────

    @ParameterizedTest
    @CsvSource(value = {
            "NULL,09:05,HASH,NULL,NULL,NULL",
            "2026-9-24,09:05,HASH,NULL,NULL,NULL",
            "2026-02-30,09:05,HASH,NULL,NULL,NULL",
            "+2026-09-24,09:05,HASH,NULL,NULL,NULL",
            "2026-09-24,9:05,HASH,NULL,NULL,NULL",
            "2026-09-24,10:00,HASH,NULL,NULL,NULL",
            "2026-09-24,NULL,HASH,NULL,NULL,NULL",
            "2026-09-24,09:05,NULL,NULL,NULL,NULL",
            "2026-09-24,09:05,AAAA,NULL,NULL,NULL",
            "2026-09-24,09:05,HASH,full,NULL,NULL",
            "2026-09-24,09:05,HASH,EMPTY,NULL,NULL",
            "2026-09-24,09:05,HASH,NULL,7E1FB982-4AE4-4E7E-BAA5-C2C80BD93491,NULL",
            "2026-09-24,09:05,HASH,NULL,not-a-uuid,NULL",
            "2026-09-24,09:05,HASH,NULL,NULL,assets",
            "2026-09-24,09:05,HASH,summary,PKG,assets",
            "2026-09-24,09:05,HASH,evidence,NULL,assets",
            "2026-09-24,09:05,HASH,evidence,PKG,NULL",
            "2026-09-24,09:05,HASH,evidence,PKG,Assets",
            "2026-09-24,09:05,HASH,evidence,PKG,1assets"}, nullValues = "NULL")
    void invalidRequestIs400BeforeOwnerOrRegistry(String date, String slot, String hash, String view, String packageId,
                                                  String sourceId) {
        user.setEffectiveUserId(null);
        SrppReadResult result = service("10:00").read(date, slot, "HASH".equals(hash) ? HASH : hash,
                "EMPTY".equals(view) ? "" : view, "PKG".equals(packageId) ? PKG : packageId, sourceId);
        assertThat(result.status()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(registry, packages, evidence, snapshots, assets);
    }

    // ───── 503 owner → 409 policy：順序 ─────

    @Test
    void missingOwnerIs503BeforePolicy() {
        user.setEffectiveUserId(null);
        SrppReadResult result = service("10:00").read("2026-09-24", "09:05", "b".repeat(64), null, null, null);
        assertThat(result.status()).isEqualTo(503);
        assertThat(code(result)).isEqualTo("OWNER_UNAVAILABLE");
        assertThat(SrppJcs.parseStrict(result.body()).path("retryable").asBoolean()).isFalse();
        verifyNoInteractions(registry, packages, evidence, snapshots, assets);
    }

    @Test
    void unknownPolicyWithWrongDateIs409PolicyUnsupported() {
        SrppReadResult result = service("10:00").read("2026-09-20", "09:05", "0".repeat(64), null, null, null);
        assertThat(result.status()).isEqualTo(409);
        assertThat(code(result)).isEqualTo("POLICY_UNSUPPORTED");
        verifyNoInteractions(packages, evidence, snapshots, assets);
    }

    // ───── latest ─────

    @Test
    void latestRequiresTodayInTaipei() {
        assertThat(code(service("10:00").read("2026-09-23", "09:05", HASH, null, null, null))).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(packages);
    }

    @Test
    void latestBeforeSlotIsNotReady() {
        assertThat(code(service("09:04:59").read("2026-09-24", "09:05", HASH, null, null, null))).isEqualTo("CONTEXT_NOT_READY");
        assertThat(code(service("11:39").read("2026-09-24", "11:40", HASH, null, null, null))).isEqualTo("CONTEXT_NOT_READY");
        verifyNoInteractions(packages);
    }

    @Test
    void latestCalendarClosedOrUnknown() {
        when(marketData.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(false));
        SrppReadResult closed = service("10:00").read("2026-09-24", "09:05", HASH, null, null, null);
        assertThat(closed.status()).isEqualTo(409);
        assertThat(code(closed)).isEqualTo("NON_TRADING_DAY");
        when(marketData.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.empty());
        SrppReadResult unknown = service("10:00").read("2026-09-24", "09:05", HASH, null, null, null);
        assertThat(unknown.status()).isEqualTo(503);
        assertThat(code(unknown)).isEqualTo("CALENDAR_UNAVAILABLE");
        assertThat(SrppJcs.parseStrict(unknown.body()).path("retryable").asBoolean()).isTrue();
        verifyNoInteractions(packages);
    }

    @Test
    void latestWithoutPackageIsNotReady() {
        when(packages.findLatestForOwner(OWNER, DATE, "11:40", HASH)).thenReturn(Optional.empty());
        SrppReadResult result = service("12:00").read("2026-09-24", "11:40", HASH, null, null, null);
        assertThat(result.status()).isEqualTo(503);
        assertThat(code(result)).isEqualTo("CONTEXT_NOT_READY");
    }

    @Test
    void latestCurrentReturnsRawContextBytesAndHash() throws Exception {
        SrppReadResult result = service("10:00").read("2026-09-24", "09:05", HASH, "summary", null, null);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.isProblem()).isFalse();
        String prefix = "{\"kind\":\"SUMMARY\",\"context\":";
        assertThat(result.body()).startsWith(prefix + pkg.getContextJcs() + ",\"contextContentSha256\":\"");
        JsonNode body = new ObjectMapper().readTree(result.body());
        assertThat(body.path("contextContentSha256").asText()).isEqualTo(SrppJcs.sha256Hex(pkg.getContextJcs()));
        assertThat(SrppJcs.hash(body.get("context"))).isEqualTo(body.path("contextContentSha256").asText());
        assertThat(body.get("freshness").toString()).isEqualTo(
                "{\"status\":\"CURRENT\",\"checkedAt\":\"2026-09-24T10:00:00+08:00\",\"changedSourceIds\":[],\"reasonCodes\":[]}");
        verify(packages).findLatestForOwner(OWNER, DATE, "09:05", HASH);
    }

    @Test
    void latestStaleIs409AndUnverifiableIs503() {
        SrppTestData changed = SrppTestData.proposal();
        changed.totalDeposit = new BigDecimal("3000001");
        when(assets.getSnapshotDetail(entity)).thenReturn(changed.snapshot());
        assertThat(code(service("10:00").read("2026-09-24", "09:05", HASH, null, null, null))).isEqualTo("CONTEXT_STALE");

        when(snapshots.findLatestWithStocksByOwnerUserId(OWNER)).thenThrow(new IllegalStateException("db"));
        assertThat(code(service("10:00").read("2026-09-24", "09:05", HASH, null, null, null))).isEqualTo("CONTEXT_NOT_READY");
    }

    @Test
    void clockBeforeGeneratedAtIsInternalError() {
        SrppReadResult latest = service("09:05").read("2026-09-24", "09:05", HASH, null, null, null);
        assertThat(latest.status()).isEqualTo(500);
        assertThat(code(latest)).isEqualTo("INTERNAL_ERROR");
        SrppReadResult pinned = service("09:05").read("2026-09-24", "09:05", HASH, null, PKG, null);
        assertThat(code(pinned)).isEqualTo("INTERNAL_ERROR");
    }

    // ───── pinned ─────

    @Test
    void otherOwnersPackageIsNotFoundAndOnlyOwnIdIsQueried() {
        UUID foreign = UUID.fromString("11111111-2222-4333-8444-555555555555");
        when(packages.findByPackageIdAndOwner(foreign, OWNER)).thenReturn(Optional.empty());
        SrppReadResult result = service("10:00").read("2026-09-24", "09:05", HASH, null, foreign.toString(), null);
        assertThat(result.status()).isEqualTo(404);
        assertThat(code(result)).isEqualTo("CONTEXT_NOT_FOUND");
        verify(packages).findByPackageIdAndOwner(foreign, OWNER);
        verify(packages, never()).findByPackageIdAndOwner(any(), eq(8L));
        verify(packages, never()).findById(any());
        verify(packages, never()).existsById(any());
    }

    @Test
    void pinnedIdentityMismatchIs409() {
        assertThat(code(service("10:00").read("2026-09-24", "11:40", HASH, null, PKG, null)))
                .isEqualTo("CONTEXT_IDENTITY_MISMATCH");
        assertThat(code(service(LocalDate.of(2026, 9, 25), "10:00").read("2026-09-25", "09:05", HASH, null, PKG, null)))
                .isEqualTo("CONTEXT_IDENTITY_MISMATCH");
        String other = "c".repeat(64);
        when(registry.find(other)).thenReturn(Optional.of(SrppTestData.policy(Map.of())));
        assertThat(code(service("10:00").read("2026-09-24", "09:05", other, null, PKG, null)))
                .isEqualTo("CONTEXT_IDENTITY_MISMATCH");
    }

    @Test
    void pinnedStaleAndUnknownStillReturn200() throws Exception {
        SrppTestData changed = SrppTestData.proposal();
        changed.totalDeposit = new BigDecimal("3000001");
        when(assets.getSnapshotDetail(entity)).thenReturn(changed.snapshot());
        when(marketData.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(false));
        SrppReadResult stale = service(LocalDate.of(2026, 9, 25), "10:00").read("2026-09-24", "09:05", HASH, null, PKG, null);
        assertThat(stale.status()).isEqualTo(200);
        assertThat(new ObjectMapper().readTree(stale.body()).get("freshness").toString()).isEqualTo(
                "{\"status\":\"STALE\",\"checkedAt\":\"2026-09-25T10:00:00+08:00\",\"changedSourceIds\":[\"assets\",\"calendar\"],"
                        + "\"reasonCodes\":[\"CALENDAR_CHANGED\",\"SOURCE_REVISION_CHANGED\"]}");

        when(assets.getSnapshotDetail(entity)).thenReturn(SrppTestData.proposal().snapshot());
        when(marketData.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.empty());
        SrppReadResult unknown = service("10:00").read("2026-09-24", "09:05", HASH, null, PKG, null);
        assertThat(unknown.status()).isEqualTo(200);
        assertThat(new ObjectMapper().readTree(unknown.body()).at("/freshness/status").asText()).isEqualTo("UNKNOWN");
        assertThat(new ObjectMapper().readTree(unknown.body()).at("/freshness/changedSourceIds")).isEmpty();
    }

    @Test
    void pinnedPreviousYearPackageIsUnknownBecauseCalendarIsCurrentYearOnly() throws Exception {
        // 2027 年 1 月讀 2026-09-24 的 pinned package：MarketDataService 只快取當年度（2027）假日，
        // cached-only 對前一年度日期必回 empty → UNKNOWN＋CALENDAR_UNVERIFIABLE，pinned 仍回 200。
        when(marketData.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.empty());
        SrppReadResult result = service(LocalDate.of(2027, 1, 5), "10:00").read("2026-09-24", "09:05", HASH, null, PKG, null);
        assertThat(result.status()).isEqualTo(200);
        JsonNode freshness = new ObjectMapper().readTree(result.body()).get("freshness");
        assertThat(freshness.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(freshness.path("changedSourceIds")).isEmpty();
        assertThat(freshness.path("reasonCodes").toString()).isEqualTo("[\"CALENDAR_UNVERIFIABLE\"]");
        assertThat(freshness.path("checkedAt").asText()).isEqualTo("2027-01-05T10:00:00+08:00");
    }

    // ───── evidence ─────

    @Test
    void evidenceReturnsEscapedBodyAndHash() throws Exception {
        SrppReadResult result = service("10:00").read("2026-09-24", "09:05", HASH, "evidence", PKG, "assets");
        assertThat(result.status()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(result.body());
        assertThat(body.path("kind").asText()).isEqualTo("EVIDENCE");
        assertThat(body.path("packageId").asText()).isEqualTo(PKG);
        assertThat(body.path("sourceId").asText()).isEqualTo("assets");
        assertThat(body.path("bodyMediaType").asText()).isEqualTo("application/json");
        assertThat(body.path("bodyEncoding").asText()).isEqualTo("UTF-8");
        assertThat(body.path("body").textValue()).isEqualTo("{\"a\":\"台股\\n\"}");
        assertThat(body.path("bodySha256").asText()).isEqualTo(SrppJcs.sha256Hex("{\"a\":\"台股\\n\"}"));
        assertThat(body.size()).isEqualTo(7);
        verify(marketData, never()).isTwTradingDayCachedOnly(any());
    }

    @Test
    void evidenceForUnknownOrMissingSourceIs404() {
        SrppReadResult unknown = service("10:00").read("2026-09-24", "09:05", HASH, "evidence", PKG, "funding");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("SOURCE_EVIDENCE_NOT_FOUND");
        verify(evidence, never()).findBody(any(), eq("funding"));
        when(evidence.findBody(SrppContextInvariantsTest.PACKAGE, "calendar")).thenReturn(Optional.empty());
        assertThat(code(service("10:00").read("2026-09-24", "09:05", HASH, "evidence", PKG, "calendar")))
                .isEqualTo("SOURCE_EVIDENCE_NOT_FOUND");
    }

    @Test
    void evidenceForeignPackageIs404() {
        UUID foreign = UUID.fromString("11111111-2222-4333-8444-555555555555");
        when(packages.findByPackageIdAndOwner(foreign, OWNER)).thenReturn(Optional.empty());
        assertThat(code(service("10:00").read("2026-09-24", "09:05", HASH, "evidence", foreign.toString(), "assets")))
                .isEqualTo("CONTEXT_NOT_FOUND");
        verifyNoInteractions(evidence);
    }

    @Test
    void problemBodiesHaveExactFieldsAndFixedText() throws Exception {
        for (String code : new String[]{"INVALID_REQUEST", "CONTEXT_NOT_FOUND", "SOURCE_EVIDENCE_NOT_FOUND", "CONTEXT_STALE",
                "POLICY_UNSUPPORTED", "CONTEXT_IDENTITY_MISMATCH", "NON_TRADING_DAY", "CALENDAR_UNAVAILABLE",
                "OWNER_UNAVAILABLE", "CONTEXT_NOT_READY", "INTERNAL_ERROR"}) {
            JsonNode problem = new ObjectMapper().readTree(SrppProblemCatalog.body(code));
            java.util.List<String> fields = new java.util.ArrayList<>();
            problem.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactly("type", "title", "status", "detail", "instance", "code", "retryable");
            assertThat(problem.path("type").asText()).isEqualTo("about:blank");
            assertThat(problem.path("instance").asText()).isEqualTo("/api/public/srpp/daily-context");
            assertThat(problem.path("retryable").asBoolean())
                    .isEqualTo(code.equals("CALENDAR_UNAVAILABLE") || code.equals("CONTEXT_NOT_READY"));
        }
    }

    @Test
    void readServiceHasNoLiveQuoteRedisPublisherOrCaptureDependency() {
        for (Constructor<?> constructor : SrppDailyContextReadService.class.getDeclaredConstructors()) {
            assertThat(Arrays.stream(constructor.getParameterTypes()).map(Class::getName))
                    .doesNotContain(StockPriceService.class.getName(), PriceQueryService.class.getName(),
                            SrppPackagePublisher.class.getName(), SrppSourceCapture.class.getName(),
                            "org.springframework.data.redis.core.RedisTemplate",
                            StringRedisTemplate.class.getName());
        }
    }
}
