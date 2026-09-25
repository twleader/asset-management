package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.SortedMap;

/**
 * Requirement 163／Task 452.4：通過伺服器端驗證的已登錄規則包。
 * {@code calculationPolicySha256 = sha256(JCS(policy_document))}；{@code formulaSetSha256} 由程式內 manifest 計算。
 */
public record SupportedPolicy(
        String bundleHash,
        String formulaVersion,
        String calculationPolicySha256,
        String formulaSetSha256,
        JsonNode policyDocument,
        JsonNode manifest,
        SortedMap<String, BigDecimal> targets,
        Instant registeredAt) {}
