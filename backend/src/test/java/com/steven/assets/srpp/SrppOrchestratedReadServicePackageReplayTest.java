package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 476：固定 package ID 經 owner-scoped evidence 與 registry 走完整按需計算讀取。 */
class SrppOrchestratedReadServicePackageReplayTest {
    private static final long OWNER = 42L;
    private static final String V1_HASH = "a".repeat(64);
    private static final String V2_HASH = "b".repeat(64);
    private static final UUID V1_ID = UUID.fromString("b5317edb-05a6-471e-b7c8-42dc741e84d1");
    private static final UUID V2_ID = UUID.fromString("75abeacf-8cda-4209-b939-317697122927");
    private static final Instant GENERATED_AT = Instant.parse("2026-09-24T01:05:00Z");
    private static final List<String> IDS = List.of("ASSET_RECONCILIATION", "CASH_INCOME");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CurrentUserContext user = new CurrentUserContext();
    private final SrppPolicyRegistryService registry = mock(SrppPolicyRegistryService.class);
    private final SrppContextPackageRepository packages = mock(SrppContextPackageRepository.class);
    private final SrppContextEvidenceRepository evidence = mock(SrppContextEvidenceRepository.class);
    private final SrppOrchestratedReadService service = new SrppOrchestratedReadService(
            user, registry, packages, evidence, null, null, null, mapper,
            Clock.fixed(GENERATED_AT, ZoneId.of("Asia/Taipei")));

    @Test
    void fixedV1AndV2PackagesReplayDifferentNullRateSemanticsWithoutChangingOldIdentity() throws Exception {
        user.setEffectiveUserId(OWNER);
        Stored v1 = stored(V1_ID, V1_HASH, SrppTestData.policy(Map.of()));
        Stored v2 = stored(V2_ID, V2_HASH, SrppTestData.policyV2(Map.of()));
        provide(v1);
        provide(v2);

        JsonNode old = read(V1_ID);
        JsonNode current = read(V2_ID);

        assertThat(old.path("contextId").asText()).isEqualTo(V1_ID.toString());
        assertThat(old.path("policyBundleSha256").asText()).isEqualTo(V1_HASH);
        assertThat(old.path("formulaSetSha256").asText()).isEqualTo(SrppFormulaCatalog.formulaSetSha256());
        assertThat(old.at("/calculations/0/calculationId").asText()).isEqualTo("ASSET_RECONCILIATION");
        assertThat(old.at("/calculations/0/formulaVersion").asText()).isEqualTo(SrppFormulaCatalog.FORMULA_VERSION);
        assertThat(old.at("/calculations/0/formulaSetSha256").asText())
                .isEqualTo(SrppFormulaCatalog.formulaSetSha256());
        assertThat(old.at("/calculations/0/data/depositGroups/0/estimatedAnnualInterest/value").asText()).isEqualTo("0");
        assertThat(old.at("/calculations/0/data/depositGroups/0/estimatedAnnualInterest/quality").asText())
                .isEqualTo("ESTIMATE");
        assertThat(old.at("/calculations/1/data/depositInterest/value").asText()).isEqualTo("0");
        assertThat(old.at("/calculations/0/reasonCodes")).isEmpty();

        assertThat(current.path("contextId").asText()).isEqualTo(V2_ID.toString());
        assertThat(current.path("policyBundleSha256").asText()).isEqualTo(V2_HASH);
        assertThat(current.path("formulaSetSha256").asText())
                .isEqualTo("0f3d9b67dcb7d519f8ef5e9ee378d86eaf26228409199bc487036732e0186e86");
        assertThat(current.at("/calculations/0/formulaVersion").asText())
                .isEqualTo(SrppFormulaCatalog.FORMULA_VERSION_V2);
        assertThat(current.at("/calculations/0/formulaSetSha256").asText())
                .isEqualTo(SrppFormulaCatalog.formulaSetSha256(SrppFormulaCatalog.FORMULA_VERSION_V2));
        assertThat(current.at("/calculations/0/data/depositGroups/0/estimatedAnnualInterest/value").isNull()).isTrue();
        assertThat(current.at("/calculations/0/data/depositGroups/0/estimatedAnnualInterest/quality").asText())
                .isEqualTo("UNAVAILABLE");
        assertThat(current.at("/calculations/0/reasonCodes").toString())
                .isEqualTo("[\"DEPOSIT_INTEREST_RATE_UNKNOWN\"]");
        assertThat(current.at("/calculations/1/data/depositInterest/value").isNull()).isTrue();
        assertThat(current.at("/calculations/1/data/sourceAccruedAnnualIncome/quality").asText())
                .isEqualTo("UNAVAILABLE");
        assertThat(current.at("/calculations/1/data/missingIncomeRowIds").toString())
                .isEqualTo("[\"DEPOSIT-1\"]");
        assertThat(current.at("/calculations/1/reasonCodes").toString())
                .doesNotContain("INCOME_RECONCILIATION_MISMATCH");
    }

    @Test
    void ownerIsolationAndTamperedEvidenceFailClosed() throws Exception {
        user.setEffectiveUserId(OWNER);
        Stored v2 = stored(V2_ID, V2_HASH, SrppTestData.policyV2(Map.of()));
        when(packages.findByPackageIdAndOwner(V2_ID, OWNER)).thenReturn(Optional.empty());
        assertThat(service.calculations(V2_ID.toString(), IDS, null))
                .isEqualTo(new SrppReadResult.Problem("CONTEXT_NOT_FOUND"));
        verify(evidence, never()).findBody(V2_ID, "assets", OWNER);

        when(packages.findByPackageIdAndOwner(V2_ID, OWNER)).thenReturn(Optional.of(v2.pkg()));
        when(registry.find(V2_HASH)).thenReturn(Optional.of(v2.policy()));
        when(evidence.findBody(V2_ID, "assets", OWNER)).thenReturn(Optional.of(v2.assetsBody() + " "));
        assertThat(service.calculations(V2_ID.toString(), IDS, null))
                .isEqualTo(new SrppReadResult.Problem("CONTEXT_STALE"));
    }

    @Test
    void packageFormulaIdentityMismatchCannotReplayUnderAnotherVersion() throws Exception {
        user.setEffectiveUserId(OWNER);
        Stored v2 = stored(V2_ID, V2_HASH, SrppTestData.policyV2(Map.of()));
        ObjectNode wrongContext = (ObjectNode) SrppJcs.parseStrict(v2.pkg().getContextJcs());
        ((ObjectNode) wrongContext.path("policy")).put("formulaVersion", SrppFormulaCatalog.FORMULA_VERSION);
        SrppContextPackage mismatch = new SrppContextPackage(V2_ID, OWNER, SrppTestData.DATE, "09:05",
                V2_HASH, GENERATED_AT, SrppJcs.canonicalize(wrongContext));
        when(packages.findByPackageIdAndOwner(V2_ID, OWNER)).thenReturn(Optional.of(mismatch));
        when(registry.find(V2_HASH)).thenReturn(Optional.of(v2.policy()));
        when(evidence.findBody(V2_ID, "assets", OWNER)).thenReturn(Optional.of(v2.assetsBody()));
        assertThat(service.calculations(V2_ID.toString(), IDS, null))
                .isEqualTo(new SrppReadResult.Problem("CONTEXT_STALE"));
    }

    private JsonNode read(UUID id) throws Exception {
        SrppReadResult result = service.calculations(id.toString(), IDS, null);
        assertThat(result).isInstanceOf(SrppReadResult.Ok.class);
        return mapper.readTree(((SrppReadResult.Ok) result).body());
    }

    private void provide(Stored stored) {
        UUID id = stored.pkg().getPackageId();
        String hash = stored.pkg().getPolicyBundleSha256();
        when(packages.findByPackageIdAndOwner(id, OWNER)).thenReturn(Optional.of(stored.pkg()));
        when(registry.find(hash)).thenReturn(Optional.of(stored.policy()));
        when(evidence.findBody(id, "assets", OWNER)).thenReturn(Optional.of(stored.assetsBody()));
    }

    private Stored stored(UUID id, String hash, SupportedPolicy original) throws Exception {
        SupportedPolicy policy = new SupportedPolicy(hash, original.formulaVersion(), original.calculationPolicySha256(),
                original.formulaSetSha256(), original.policyDocument(), original.manifest(), original.targets(),
                original.registeredAt());
        String body = mapper.writeValueAsString(new SrppTestData()
                .deposit(1, 10L, "bank", "DEMAND", "100", null, "TWD", null).build());
        ObjectNode context = mapper.createObjectNode();
        context.put("packageId", id.toString());
        context.put("tradingDate", SrppTestData.DATE.toString());
        context.put("slot", "09:05");
        ObjectNode policyNode = context.putObject("policy");
        policyNode.put("policyBundleSha256", hash);
        policyNode.put("formulaVersion", policy.formulaVersion());
        policyNode.put("formulaSetSha256", policy.formulaSetSha256());
        ObjectNode source = context.putArray("sources").addObject();
        source.put("sourceId", "assets");
        source.put("state", "AVAILABLE");
        source.put("revision", "snapshot-1-test");
        source.put("dataAsOf", "2026-09-24T09:04:00+08:00");
        source.put("bodySha256", SrppJcs.sha256Hex(body));
        SrppContextPackage pkg = new SrppContextPackage(id, OWNER, SrppTestData.DATE, "09:05",
                hash, GENERATED_AT, SrppJcs.canonicalize(context));
        return new Stored(pkg, policy, body);
    }

    private record Stored(SrppContextPackage pkg, SupportedPolicy policy, String assetsBody) {}
}
