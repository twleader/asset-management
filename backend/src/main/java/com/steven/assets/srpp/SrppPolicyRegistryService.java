package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Requirement 163／Task 452.4：政策 registry 的伺服器端驗證。
 *
 * <p>每次使用前都驗證三項：{@code formula_version} 為本程式實作的版本、{@code formula_manifest} 以 JCS 比對
 * 等於程式內建 manifest、{@code policy_document} 通過 {@code SRPP_POLICY_DOCUMENT_V1} 結構驗證。未通過者視為
 * 不存在（WARN 只記一次，只含 hash 前 12 碼與原因碼）。呼叫端提供的 hash 只當查詢鍵，絕不 echo 冒充支援。
 */
@Slf4j
@Service
public class SrppPolicyRegistryService {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    private final SrppPolicyRegistryRepository repository;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public SrppPolicyRegistryService(SrppPolicyRegistryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<SupportedPolicy> find(String bundleHash) {
        if (bundleHash == null || !HASH.matcher(bundleHash).matches()) return Optional.empty();
        return repository.findById(bundleHash).flatMap(this::validate);
    }

    @Transactional(readOnly = true)
    public List<SupportedPolicy> supportedPolicies() {
        return repository.findAll().stream()
                .map(this::validate)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(SupportedPolicy::bundleHash))
                .toList();
    }

    Optional<SupportedPolicy> validate(SrppPolicyRegistryEntry entry) {
        String hash = entry.getPolicyBundleSha256();
        if (hash == null || !HASH.matcher(hash).matches()) return reject(hash, "BUNDLE_HASH_INVALID");
        if (!SrppFormulaCatalog.FORMULA_VERSION.equals(entry.getFormulaVersion())) {
            return reject(hash, "FORMULA_VERSION_UNSUPPORTED");
        }
        JsonNode manifest;
        try {
            manifest = SrppJcs.parseStrict(entry.getFormulaManifest());
            if (!SrppFormulaCatalog.manifestJcs().equals(SrppJcs.canonicalize(manifest))) {
                return reject(hash, "FORMULA_MANIFEST_MISMATCH");
            }
        } catch (IllegalArgumentException e) {
            return reject(hash, "FORMULA_MANIFEST_INVALID");
        }
        SrppPolicyDocument document;
        try {
            document = SrppPolicyDocument.parse(entry.getPolicyDocument());
        } catch (IllegalArgumentException e) {
            return reject(hash, "POLICY_DOCUMENT_INVALID");
        }
        if (entry.getRegisteredAt() == null) return reject(hash, "REGISTERED_AT_MISSING");
        return Optional.of(new SupportedPolicy(
                hash,
                SrppFormulaCatalog.FORMULA_VERSION,
                SrppJcs.hash(document.document()),
                SrppFormulaCatalog.formulaSetSha256(),
                document.document(),
                manifest,
                document.targets(),
                entry.getRegisteredAt()));
    }

    private Optional<SupportedPolicy> reject(String hash, String reason) {
        String prefix = hash == null ? "null" : hash.substring(0, Math.min(12, hash.length()));
        if (warned.add(prefix + ":" + reason)) {
            log.warn("SRPP 規則包未通過驗證，視為未支援 hash={} reason={}", prefix, reason);
        }
        return Optional.empty();
    }
}
