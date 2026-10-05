package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.bytes;
import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.evidence;
import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.latest;
import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.pinned;
import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.rehash;
import static com.steven.assets.bff.publicsrpp.SrppTestFixtures.tree;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 163／Task 454.3：proposal 合成範例為正例，逐條規則反例一律拒絕。 */
class SrppDailyContextResponseValidatorTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    // ------------------------------------------------------------------ positives

    @Test
    void proposalSummariesPassWithMatchingQuery() {
        accept(latest(), tree("summary-complete.json"));
        accept(latest(), tree("summary-partial.json"));
        accept(pinned(SrppTestFixtures.PARTIAL_PACKAGE), tree("summary-partial.json"));
        accept(pinned(SrppTestFixtures.PARTIAL_PACKAGE), tree("summary-stale-pinned.json"));
    }

    @Test
    void v2UnknownRateRequiresConsistentGroupAndIncomeQuality() {
        ObjectNode good = v2UnknownRate();
        accept(latest(), good);
        ObjectNode falseZero = good.deepCopy();
        ObjectNode interest = (ObjectNode) falseZero.at("/context/modules/assets/data/depositGroups/0/estimatedAnnualInterest");
        interest.put("value", "0").put("quality", "ESTIMATE");
        interest.putArray("reasonCodes");
        interest.putArray("sourceIds").add("assets");
        reject(latest(), rehash(falseZero));
        ObjectNode falseExact = good.deepCopy();
        ObjectNode exactInterest = (ObjectNode) falseExact.at("/context/modules/assets/data/depositGroups/1/estimatedAnnualInterest");
        exactInterest.put("quality", "EXACT");
        reject(latest(), rehash(falseExact));
        ObjectNode wrongDigest = good.deepCopy();
        ((ObjectNode) wrongDigest.at("/context/policy")).put("formulaSetSha256", "a".repeat(64));
        reject(latest(), rehash(wrongDigest));
        ObjectNode wrongIncome = good.deepCopy();
        ((ObjectNode) wrongIncome.at("/context/modules/cashIncome/data/sourceAccruedAnnualIncome"))
                .put("value", "90000").put("quality", "ESTIMATE").putArray("reasonCodes");
        ((ObjectNode) wrongIncome.at("/context/modules/cashIncome/data/sourceAccruedAnnualIncome"))
                .putArray("sourceIds").add("assets");
        reject(latest(), rehash(wrongIncome));
    }

    @Test
    void proposalEvidencesPassWithMatchingQuery() {
        for (String name : SrppTestFixtures.EVIDENCES) {
            JsonNode node = tree(name);
            accept(evidence(node.get("packageId").textValue(), node.get("sourceId").textValue()), node);
        }
    }

    @Test
    void depositGroupBankIdsCompareAsWireStringsNotNumbers() {
        ObjectNode ascendingAsStrings = withBankIds("10", "9");
        accept(latest(), ascendingAsStrings);
        ObjectNode numericOrder = withBankIds("9", "10");
        reject(latest(), numericOrder);
    }

    @Test
    void nullBankIdSortsFirst() {
        accept(latest(), withBankIds(null, "9"));
        reject(latest(), withBankIds("9", null));
    }

    // ------------------------------------------------------------------ negatives

    @Test
    void rejectsExtraAndMissingFields() {
        reject(latest(), summary(ctx -> ctx.put("extra", "x")));
        reject(latest(), rehash(mutate(root -> ((ObjectNode) root.get("freshness")).remove("reasonCodes"))));
        reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).put("note", "x")));
        reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).remove("sourceIds")));
    }

    @Test
    void rejectsWrongEnumAndConstants() {
        reject(latest(), summary(ctx -> ctx.put("coverage", "FULL")));
        reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).put("quality", "GUESS")));
        reject(latest(), summary(ctx -> ctx.put("tradingAuthorized", true)));
        reject(latest(), summary(ctx -> ctx.put("schemaVersion", "1.0.1")));
        reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).put("unit", "RATIO")));
    }

    @Test
    void rejectsNonCanonicalDecimals() {
        reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).put("value", 3000000)));
        reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).put("value", true)));
        for (String bad : new String[]{"1.0", "-0", "1e3", "01", "1,000", "NaN", "Infinity", "", " 1"}) {
            reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).put("value", bad)));
        }
        reject(latest(), summary(ctx -> check(ctx, 0).put("toleranceTwd", "300.0")));
    }

    @Test
    void rejectsMetricConditionViolations() {
        reject(latest(), summary(ctx -> {
            ObjectNode metric = (ObjectNode) metric(ctx);
            metric.put("quality", "UNAVAILABLE");
            metric.putArray("reasonCodes").add("NOT_KNOWN");
        }));
        reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).put("quality", "UNAVAILABLE").putNull("value")));
        reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).putArray("sourceIds")));
        reject(latest(), summary(ctx -> ((ObjectNode) metric(ctx)).putArray("reasonCodes").add("lower_case")));
    }

    @Test
    void rejectsCompleteModuleContainingUnavailableMetric() {
        reject(latest(), summary(ctx -> {
            ObjectNode interest = (ObjectNode) assetsData(ctx).get("depositGroups").get(0).get("estimatedAnnualInterest");
            interest.putNull("value");
            interest.put("quality", "UNAVAILABLE");
            interest.putArray("reasonCodes").add("RATE_MISSING");
            interest.putArray("sourceIds");
        }));
    }

    @Test
    void rejectsIncompleteOrDuplicateChecks() {
        reject(latest(), summary(ctx -> ((ArrayNode) assetsData(ctx).get("checks")).remove(7)));
        reject(latest(), summary(ctx -> check(ctx, 7).put("name", "snapshot_deposits")));
        reject(latest(), summary(ctx -> check(ctx, 0).put("passed", false)));
    }

    @Test
    void rejectsUnsortedIdsAndMissingSourceReferences() {
        reject(latest(), summary(ctx -> {
            ArrayNode ids = ((ObjectNode) ctx.get("modules").get("allocation")).putArray("sourceIds");
            ids.add("policy").add("assets");
        }));
        reject(latest(), summary(ctx -> {
            ObjectNode policy = (ObjectNode) ctx.get("sources").get(2);
            policy.put("state", "MISSING");
            policy.putNull("revision");
            policy.putNull("capturedAt");
            policy.putNull("dataAsOf");
            policy.putNull("bodySha256");
            policy.putArray("reasonCodes").add("POLICY_NOT_CAPTURED");
        }));
        reject(latest(), summary(ctx -> {
            ArrayNode sources = (ArrayNode) ctx.get("sources");
            JsonNode first = sources.remove(0);
            sources.add(first);
        }));
    }

    @Test
    void rejectsCoverageOverReport() {
        reject(latest(), summary(ctx -> ctx.put("coverage", "COMPLETE")));
    }

    @Test
    void rejectsTimeOrderViolationsAndOffsetlessTimes() {
        reject(latest(), summary(ctx -> ctx.put("dataCutoffAt", "2026-09-24T09:06:00+08:00")));
        reject(latest(), summary(ctx -> ctx.put("generatedAt", "2026-09-24T09:05:01")));
        reject(latest(), summary(ctx -> ((ObjectNode) ctx.get("sources").get(0)).put("dataAsOf", "2026-09-24T09:05:02+08:00")));
    }

    @Test
    void rejectsAlteredContextHash() {
        ObjectNode root = tree("summary-partial.json");
        String hash = root.get("contextContentSha256").textValue();
        root.put("contextContentSha256", (hash.charAt(0) == 'a' ? "b" : "a") + hash.substring(1));
        reject(latest(), root);
        reject(latest(), mutate(r -> ((ObjectNode) r.get("context")).put("ownerKey", "other-owner")));
    }

    @Test
    void rejectsAlteredEvidenceBodyAndDuplicateKeyBody() {
        ObjectNode altered = tree("evidence-assets.json");
        altered.put("body", altered.get("body").textValue().replace("3000000", "3000001"));
        reject(evidence(SrppTestFixtures.PARTIAL_PACKAGE, "assets"), altered);

        ObjectNode duplicate = tree("evidence-assets.json");
        String body = "{\"a\":1,\"a\":2}";
        duplicate.put("body", body);
        duplicate.put("bodySha256", SrppJcs.sha256Hex(body));
        reject(evidence(SrppTestFixtures.PARTIAL_PACKAGE, "assets"), duplicate);

        reject(evidence(SrppTestFixtures.PARTIAL_PACKAGE, "policy"), tree("evidence-assets.json"));
    }

    @Test
    void latestModeRejectsStaleAndIdentityMismatchesAreRejected() {
        reject(latest(), tree("summary-stale-pinned.json"));
        reject(SrppTestFixtures.query("tradingDate", "2026-09-25", "slot", "09:05",
                "policyBundleSha256", SrppTestFixtures.HASH), tree("summary-partial.json"));
        reject(SrppTestFixtures.query("tradingDate", "2026-09-24", "slot", "11:40",
                "policyBundleSha256", SrppTestFixtures.HASH), tree("summary-partial.json"));
        reject(SrppTestFixtures.query("tradingDate", "2026-09-24", "slot", "09:05",
                "policyBundleSha256", "0".repeat(64)), tree("summary-partial.json"));
        reject(pinned("96c331dd-0015-4646-8a8c-9a304de0f322"), tree("summary-partial.json"));
        reject(latest(), tree("evidence-assets.json"));
    }

    @Test
    void rejectsWrongContentTypeDuplicateKeysAndTrailingTokens() {
        byte[] ok = bytes(tree("summary-partial.json"));
        assertThatThrownBy(() -> SrppDailyContextResponseValidator.validate(latest(), MediaType.TEXT_HTML, ok))
                .isInstanceOf(SrppPayloadException.class);
        assertThatThrownBy(() -> SrppDailyContextResponseValidator.validate(latest(), null, ok))
                .isInstanceOf(SrppPayloadException.class);
        String raw = new String(ok, StandardCharsets.UTF_8);
        byte[] duplicated = raw.replaceFirst("\\{\"kind\":\"SUMMARY\",", "{\"kind\":\"SUMMARY\",\"kind\":\"SUMMARY\",")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SrppDailyContextResponseValidator.validate(latest(), JSON, duplicated))
                .isInstanceOf(SrppPayloadException.class);
        byte[] trailing = (raw + "{}").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SrppDailyContextResponseValidator.validate(latest(), JSON, trailing))
                .isInstanceOf(SrppPayloadException.class);
        assertThatThrownBy(() -> SrppDailyContextResponseValidator.validate(latest(), JSON, new byte[0]))
                .isInstanceOf(SrppPayloadException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static void accept(SrppDailyContextQuery query, JsonNode root) {
        assertThatCode(() -> SrppDailyContextResponseValidator.validate(query, JSON, bytes(root))).doesNotThrowAnyException();
    }

    private static void reject(SrppDailyContextQuery query, JsonNode root) {
        assertThatThrownBy(() -> SrppDailyContextResponseValidator.validate(query, JSON, bytes(root)))
                .isInstanceOf(SrppPayloadException.class);
    }

    /** 以 summary-partial 為底改 context，並重算 hash，確保反例只違反被測規則。 */
    private static ObjectNode summary(Consumer<ObjectNode> contextMutation) {
        ObjectNode root = tree("summary-partial.json");
        contextMutation.accept((ObjectNode) root.get("context"));
        return rehash(root);
    }

    private static ObjectNode mutate(Consumer<ObjectNode> rootMutation) {
        ObjectNode root = tree("summary-partial.json");
        rootMutation.accept(root);
        return root;
    }

    private static ObjectNode assetsData(ObjectNode ctx) {
        return (ObjectNode) ctx.get("modules").get("assets").get("data");
    }

    private static ObjectNode v2UnknownRate() {
        ObjectNode root = tree("summary-partial.json");
        ObjectNode ctx = (ObjectNode) root.get("context");
        ((ObjectNode) ctx.get("policy")).put("formulaVersion", "ASSET_MGMT_SRPP_V2")
                .put("formulaSetSha256", "0f3d9b67dcb7d519f8ef5e9ee378d86eaf26228409199bc487036732e0186e86");
        ObjectNode modules = (ObjectNode) ctx.get("modules");
        ((ObjectNode) modules.get("assets")).put("status", "PARTIAL")
                .put("calculationId", "ASSET_MGMT_ASSETS_RECON_V2")
                .putArray("reasonCodes").add("DEPOSIT_INTEREST_RATE_UNKNOWN");
        ((ObjectNode) modules.get("allocation")).put("calculationId", "ASSET_MGMT_TOTAL_EXPOSURE_V1");
        ((ObjectNode) modules.get("cashIncome")).put("calculationId", "ASSET_MGMT_GROSS_INCOME_V2")
                .putArray("reasonCodes").add("DEPOSIT_INTEREST_RATE_UNKNOWN")
                .add("INCOME_ROWS_MISSING").add("NET_CALCULATION_NOT_VERIFIED");
        ((ObjectNode) modules.get("funding")).put("calculationId", "ASSET_MGMT_FUNDING_UNAVAILABLE_V1");
        ((ObjectNode) modules.get("completedTechnicals")).put("calculationId", "ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1");
        ObjectNode group = (ObjectNode) ctx.at("/modules/assets/data/depositGroups/0");
        group.putArray("sourceRowIds").add("DEPOSIT-2");
        unavailable((ObjectNode) group.get("estimatedAnnualInterest"));
        ObjectNode cash = (ObjectNode) ctx.at("/modules/cashIncome/data");
        cash.putArray("missingIncomeRowIds").add("DEPOSIT-2");
        unavailable((ObjectNode) cash.get("depositInterest"));
        unavailable((ObjectNode) cash.get("sourceAccruedAnnualIncome"));
        return rehash(root);
    }

    private static void unavailable(ObjectNode metric) {
        metric.putNull("value");
        metric.put("quality", "UNAVAILABLE");
        metric.putArray("reasonCodes").add("DEPOSIT_INTEREST_RATE_UNKNOWN");
        metric.putArray("sourceIds");
    }

    private static JsonNode metric(ObjectNode ctx) {
        return assetsData(ctx).get("snapshotTotalDeposit");
    }

    private static ObjectNode check(ObjectNode ctx, int index) {
        return (ObjectNode) assetsData(ctx).get("checks").get(index);
    }

    private static ObjectNode withBankIds(String first, String second) {
        return summary(ctx -> {
            ArrayNode groups = (ArrayNode) assetsData(ctx).get("depositGroups");
            ObjectNode a = (ObjectNode) groups.get(0);
            ObjectNode b = a.deepCopy();
            a.put("depositType", "DEMAND");
            b.put("depositType", "DEMAND");
            if (first == null) a.putNull("bankId"); else a.put("bankId", first);
            if (second == null) b.putNull("bankId"); else b.put("bankId", second);
            b.putArray("sourceRowIds").add("3");
            groups.removeAll();
            groups.add(a).add(b);
        });
    }
}
