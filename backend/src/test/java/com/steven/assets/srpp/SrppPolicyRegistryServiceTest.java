package com.steven.assets.srpp;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Requirement 163／Task 452.4：registry 伺服器端驗證；query hash 只當查詢鍵，不 echo 冒充支援。 */
class SrppPolicyRegistryServiceTest {
    static final String HASH = "a".repeat(64);
    static final String POLICY = "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"CASH:TWD:活期\":\"0.4\"}}";

    private final SrppPolicyRegistryRepository repository = mock(SrppPolicyRegistryRepository.class);
    private final SrppPolicyRegistryService service = new SrppPolicyRegistryService(repository);

    static SrppPolicyRegistryEntry entry(String hash, String version, String policy, String manifest) {
        return new SrppPolicyRegistryEntry(hash, version, policy, manifest, Instant.parse("2026-09-24T00:00:00Z"));
    }

    @Test
    void validEntryIsSupportedWithServerComputedHashes() {
        when(repository.findById(HASH)).thenReturn(Optional.of(
                entry(HASH, "ASSET_MGMT_SRPP_V1", POLICY, SrppFormulaCatalog.MANIFEST_JSON)));

        SupportedPolicy policy = service.find(HASH).orElseThrow();

        assertThat(policy.bundleHash()).isEqualTo(HASH);
        assertThat(policy.formulaSetSha256()).isEqualTo(SrppFormulaCatalogTest.GOLDEN_FORMULA_SET_SHA256);
        assertThat(policy.calculationPolicySha256()).isEqualTo(SrppJcs.hash(SrppJcs.parseStrict(POLICY)));
        assertThat(policy.calculationPolicySha256()).isNotEqualTo(HASH);
        assertThat(policy.targets()).containsOnlyKeys("CASH:TWD:活期");
    }

    @Test
    void manifestWithDifferentKeyOrderButSameJcsIsAccepted() {
        String reordered = SrppJcs.canonicalize(SrppFormulaCatalog.manifest());
        when(repository.findById(HASH)).thenReturn(Optional.of(entry(HASH, "ASSET_MGMT_SRPP_V1", POLICY, reordered)));
        assertThat(service.find(HASH)).isPresent();
    }

    @Test
    void unsupportedVersionManifestOrPolicyIsTreatedAsAbsent() {
        String otherManifest = SrppFormulaCatalog.MANIFEST_JSON.replace("MathContext(34,HALF_EVEN)", "MathContext(16,HALF_UP)");
        when(repository.findAll()).thenReturn(List.of(
                entry("b".repeat(64), "OTHER_V1", POLICY, SrppFormulaCatalog.MANIFEST_JSON),
                entry("c".repeat(64), "ASSET_MGMT_SRPP_V1", POLICY, otherManifest),
                entry("d".repeat(64), "ASSET_MGMT_SRPP_V1", "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A\":0.1}}",
                        SrppFormulaCatalog.MANIFEST_JSON),
                entry("e".repeat(64), "ASSET_MGMT_SRPP_V1", POLICY, "not json"),
                entry(HASH, "ASSET_MGMT_SRPP_V1", POLICY, SrppFormulaCatalog.MANIFEST_JSON)));

        assertThat(service.supportedPolicies()).extracting(SupportedPolicy::bundleHash).containsExactly(HASH);
    }

    @Test
    void emptyRegistryHasNoSupportedPolicies() {
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.supportedPolicies()).isEmpty();
    }

    @Test
    void malformedQueryHashNeverTouchesRepository() {
        assertThat(service.find("A".repeat(64))).isEmpty();
        assertThat(service.find(null)).isEmpty();
        assertThat(service.find("0".repeat(63))).isEmpty();
        verify(repository, never()).findById(anyString());
    }

    @Test
    void unknownHashIsUnsupported() {
        when(repository.findById("0".repeat(64))).thenReturn(Optional.empty());
        assertThat(service.find("0".repeat(64))).isEmpty();
    }
}
