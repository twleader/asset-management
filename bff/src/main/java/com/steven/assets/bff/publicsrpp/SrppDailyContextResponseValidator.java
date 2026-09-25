package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * business {@code /internal/public-srpp/daily-context} 2xx 回應的 strict validator（純函式；Requirement 163）。
 *
 * <p>依 proposal {@code Srpp*} schema 逐欄驗證（所有 object 皆 {@code additionalProperties:false}）、canonical
 * Decimal、Metric／模組條件、排序與唯一、source 引用、時間序、coverage，並以 BFF 自有 {@link SrppJcs} 重算
 * {@code contextContentSha256}／{@code bodySha256}，最後比對回應身分與 query 一致。任一不符拋
 * {@link SrppPayloadException}；呼叫端轉 502 {@code UPSTREAM_INVALID}，絕不 200 放行。
 *
 * <p>排序比較器與 producer 完全一致：sources 依 {@code sourceId}、allocation rows 依 {@code assetKey}、
 * depositGroups 依 {@code (currency, depositType, bankId)}（bankId 以 wire 字串比較、null 最前），全部用
 * {@link String#compareTo}；ID 陣列亦依 {@link String#compareTo} 嚴格遞增（兼驗唯一）。
 */
public final class SrppDailyContextResponseValidator {

    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private static final Pattern DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    private static final Pattern DATE_TIME = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$");

    private static final Set<String> SOURCE_KINDS = Set.of("ASSETS", "POLICY", "CALENDAR", "DAILY_BARS",
            "CORPORATE_ACTIONS", "ETF_COMPONENTS", "TEN_YEAR_HISTORY", "BOND_FORWARD_YIELDS", "TAX_INPUTS");
    private static final Set<String> QUALITIES = Set.of("EXACT", "ESTIMATE", "UPPER_BOUND", "LOWER_BOUND", "UNAVAILABLE");
    private static final Set<String> MODULE_STATUSES = Set.of("COMPLETE", "PARTIAL", "UNAVAILABLE");
    private static final Set<String> CHECK_NAMES = Set.of("snapshot_deposits", "live_deposits", "snapshot_funds",
            "live_funds", "snapshot_stocks", "live_stocks", "snapshot_total", "live_total");
    private static final Set<String> FRESHNESS = Set.of("CURRENT", "STALE", "UNKNOWN");

    private static final String TWD = "TWD";
    private static final String RATIO = "RATIO";
    private static final String NATIVE_PRICE = "NATIVE_PRICE";

    private SrppDailyContextResponseValidator() {}

    /** 驗證 business 2xx 回應；通過時回傳即可原位元組轉送。 */
    public static void validate(SrppDailyContextQuery query, MediaType contentType, byte[] body) {
        if (!isApplicationJson(contentType)) fail("Content-Type 必須是 application/json");
        if (body == null || body.length == 0) fail("空 body");
        JsonNode root = parseStrict(body);
        if (query.view() == SrppDailyContextQuery.View.EVIDENCE) {
            validateEvidence(query, root);
        } else {
            validateSummary(query, root);
        }
    }

    /** 以與回應相同的嚴格規則解析 JSON：拒絕重複 key、NaN／Infinity、尾端多餘 token。 */
    static JsonNode parseStrict(byte[] bytes) {
        try {
            JsonNode node = STRICT.readTree(bytes);
            if (node == null || node.isMissingNode()) fail("JSON 為空");
            return node;
        } catch (IOException ex) {
            throw new SrppPayloadException("JSON 無法嚴格解析", ex);
        }
    }

    private static boolean isApplicationJson(MediaType contentType) {
        return contentType != null
                && "application".equalsIgnoreCase(contentType.getType())
                && "json".equalsIgnoreCase(contentType.getSubtype());
    }

    // ---------------------------------------------------------------- evidence

    private static void validateEvidence(SrppDailyContextQuery query, JsonNode root) {
        exactFields(root, "$", "kind", "packageId", "sourceId", "bodyMediaType", "bodyEncoding", "body", "bodySha256");
        constText(root, "kind", "EVIDENCE", "$");
        String packageId = uuid(root.get("packageId"), "$.packageId");
        String sourceId = id(root.get("sourceId"), "$.sourceId");
        constText(root, "bodyMediaType", "application/json", "$");
        constText(root, "bodyEncoding", "UTF-8", "$");
        String body = text(root.get("body"), "$.body");
        String bodySha = sha(root.get("bodySha256"), "$.bodySha256");
        if (!packageId.equals(query.packageId())) fail("$.packageId 與 query 不符");
        if (!sourceId.equals(query.sourceId())) fail("$.sourceId 與 query 不符");
        if (!SrppJcs.sha256Hex(body).equals(bodySha)) fail("$.bodySha256 與 body 不符");
        parseStrict(body.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- summary

    private static void validateSummary(SrppDailyContextQuery query, JsonNode root) {
        exactFields(root, "$", "kind", "context", "contextContentSha256", "freshness");
        constText(root, "kind", "SUMMARY", "$");
        String declaredHash = sha(root.get("contextContentSha256"), "$.contextContentSha256");
        JsonNode context = root.get("context");
        Instant generatedAt = new Run().context(context);
        String actualHash;
        try {
            actualHash = SrppJcs.sha256Hex(SrppJcs.canonicalize(context));
        } catch (IllegalArgumentException ex) {
            throw new SrppPayloadException("$.context 無法 JCS", ex);
        }
        if (!actualHash.equals(declaredHash)) fail("$.contextContentSha256 與 JCS(context) 不符");

        String freshnessStatus = freshness(root.get("freshness"), generatedAt);

        if (!context.get("tradingDate").textValue().equals(query.tradingDate())) fail("$.context.tradingDate 與 query 不符");
        if (!context.get("slot").textValue().equals(query.slot())) fail("$.context.slot 與 query 不符");
        if (!context.get("policy").get("policyBundleSha256").textValue().equals(query.policyBundleSha256())) {
            fail("$.context.policy.policyBundleSha256 與 query 不符");
        }
        if (query.packageId() != null && !context.get("packageId").textValue().equals(query.packageId())) {
            fail("$.context.packageId 與 query 不符");
        }
        if (query.latestMode() && !"CURRENT".equals(freshnessStatus)) fail("最新模式只接受 CURRENT");
    }

    private static String freshness(JsonNode node, Instant generatedAt) {
        String path = "$.freshness";
        exactFields(node, path, "status", "checkedAt", "changedSourceIds", "reasonCodes");
        String status = enumText(node.get("status"), FRESHNESS, path + ".status");
        Instant checkedAt = dateTime(node.get("checkedAt"), path + ".checkedAt");
        List<String> changed = idArray(node.get("changedSourceIds"), path + ".changedSourceIds");
        int reasons = reasonCodes(node.get("reasonCodes"), path + ".reasonCodes");
        if ("CURRENT".equals(status)) {
            if (!changed.isEmpty() || reasons != 0) fail(path + " CURRENT 不得有 changedSourceIds／reasonCodes");
        } else if (reasons < 1) {
            fail(path + " 非 CURRENT 必須有 reasonCodes");
        }
        if (generatedAt.isAfter(checkedAt)) fail(path + ".checkedAt 早於 generatedAt");
        return status;
    }

    /** 單次 context 驗證的狀態（AVAILABLE source 集合）。 */
    private static final class Run {
        private final Set<String> availableSources = new HashSet<>();

        Instant context(JsonNode ctx) {
            String path = "$.context";
            exactFields(ctx, path, "packageId", "schemaVersion", "generatedAt", "dataCutoffAt", "tradingDate",
                    "slot", "timezone", "ownerKey", "assetSnapshotId", "assetSnapshotDate", "policy", "coverage",
                    "sources", "modules", "tradingAuthorized", "requiresOriginalDailyChecks");
            uuid(ctx.get("packageId"), path + ".packageId");
            constText(ctx, "schemaVersion", "1.0.0", path);
            Instant generatedAt = dateTime(ctx.get("generatedAt"), path + ".generatedAt");
            Instant dataCutoffAt = dateTime(ctx.get("dataCutoffAt"), path + ".dataCutoffAt");
            date(ctx.get("tradingDate"), path + ".tradingDate");
            enumText(ctx.get("slot"), Set.of("09:05", "11:40"), path + ".slot");
            constText(ctx, "timezone", "Asia/Taipei", path);
            id(ctx.get("ownerKey"), path + ".ownerKey");
            id(ctx.get("assetSnapshotId"), path + ".assetSnapshotId");
            date(ctx.get("assetSnapshotDate"), path + ".assetSnapshotDate");
            policy(ctx.get("policy"), path + ".policy");
            String coverage = enumText(ctx.get("coverage"), Set.of("COMPLETE", "PARTIAL"), path + ".coverage");
            sources(ctx.get("sources"), path + ".sources", generatedAt);
            constBool(ctx, "tradingAuthorized", false, path);
            constBool(ctx, "requiresOriginalDailyChecks", true, path);
            if (dataCutoffAt.isAfter(generatedAt)) fail(path + ".dataCutoffAt 晚於 generatedAt");

            JsonNode modules = ctx.get("modules");
            String mp = path + ".modules";
            exactFields(modules, mp, "assets", "allocation", "cashIncome", "funding", "completedTechnicals");
            List<String> statuses = List.of(
                    module(modules.get("assets"), mp + ".assets", this::assetsData),
                    module(modules.get("allocation"), mp + ".allocation", this::allocationData),
                    module(modules.get("cashIncome"), mp + ".cashIncome", this::cashIncomeData),
                    module(modules.get("funding"), mp + ".funding", this::fundingData),
                    module(modules.get("completedTechnicals"), mp + ".completedTechnicals", this::technicalsData));
            String expectedCoverage = statuses.stream().allMatch("COMPLETE"::equals) ? "COMPLETE" : "PARTIAL";
            if (!expectedCoverage.equals(coverage)) fail(path + ".coverage 與模組狀態不一致");
            return generatedAt;
        }

        private void policy(JsonNode node, String path) {
            exactFields(node, path, "policyBundleSha256", "calculationPolicySha256", "formulaSetSha256",
                    "formulaVersion", "scope");
            sha(node.get("policyBundleSha256"), path + ".policyBundleSha256");
            sha(node.get("calculationPolicySha256"), path + ".calculationPolicySha256");
            sha(node.get("formulaSetSha256"), path + ".formulaSetSha256");
            nonEmptyText(node.get("formulaVersion"), path + ".formulaVersion");
            constText(node, "scope", "LISTED_CALCULATIONS_ONLY", path);
        }

        private void sources(JsonNode node, String path, Instant generatedAt) {
            if (node == null || !node.isArray() || node.size() < 2) fail(path + " 至少兩個 source");
            String previous = null;
            for (int i = 0; i < node.size(); i++) {
                JsonNode source = node.get(i);
                String sp = path + "[" + i + "]";
                exactFields(source, sp, "sourceId", "kind", "state", "revision", "capturedAt", "dataAsOf",
                        "bodySha256", "reasonCodes");
                String sourceId = id(source.get("sourceId"), sp + ".sourceId");
                if (previous != null && previous.compareTo(sourceId) >= 0) fail(path + " 未依 sourceId 排序或重複");
                previous = sourceId;
                enumText(source.get("kind"), SOURCE_KINDS, sp + ".kind");
                String state = enumText(source.get("state"), Set.of("AVAILABLE", "MISSING"), sp + ".state");
                int reasons = reasonCodes(source.get("reasonCodes"), sp + ".reasonCodes");
                if ("AVAILABLE".equals(state)) {
                    id(source.get("revision"), sp + ".revision");
                    Instant captured = dateTime(source.get("capturedAt"), sp + ".capturedAt");
                    Instant dataAsOf = dateTime(source.get("dataAsOf"), sp + ".dataAsOf");
                    sha(source.get("bodySha256"), sp + ".bodySha256");
                    if (reasons != 0) fail(sp + " AVAILABLE 不得有 reasonCodes");
                    if (dataAsOf.isAfter(captured) || captured.isAfter(generatedAt)) {
                        fail(sp + " 時間序須 dataAsOf ≤ capturedAt ≤ generatedAt");
                    }
                    availableSources.add(sourceId);
                } else {
                    for (String field : List.of("revision", "capturedAt", "dataAsOf", "bodySha256")) {
                        if (!source.get(field).isNull()) fail(sp + "." + field + " MISSING 必須為 null");
                    }
                    if (reasons < 1) fail(sp + " MISSING 必須有 reasonCodes");
                }
            }
        }

        // ---------------------------------------------------------- modules

        private String module(JsonNode node, String path, DataValidator data) {
            exactFields(node, path, "status", "reasonCodes", "sourceIds", "calculationId", "data");
            String status = enumText(node.get("status"), MODULE_STATUSES, path + ".status");
            int reasons = reasonCodes(node.get("reasonCodes"), path + ".reasonCodes");
            List<String> sources = sourceIds(node.get("sourceIds"), path + ".sourceIds");
            id(node.get("calculationId"), path + ".calculationId");
            JsonNode dataNode = node.get("data");
            if ("UNAVAILABLE".equals(status)) {
                if (!dataNode.isNull()) fail(path + ".data UNAVAILABLE 必須為 null");
                if (reasons < 1) fail(path + " UNAVAILABLE 必須有 reasonCodes");
                return status;
            }
            if (!dataNode.isObject()) fail(path + ".data 必須是 object");
            if (sources.isEmpty()) fail(path + ".sourceIds 至少一項");
            Scan scan = new Scan();
            data.validate(dataNode, path + ".data", scan);
            if ("COMPLETE".equals(status)) {
                if (reasons != 0) fail(path + " COMPLETE 不得有 reasonCodes");
                if (scan.unavailableMetric) fail(path + " COMPLETE 不得含 UNAVAILABLE Metric");
                if (scan.gaps) fail(path + " COMPLETE 不得有未映射或缺列");
            } else if (reasons < 1) {
                fail(path + " PARTIAL 必須有 reasonCodes");
            }
            return status;
        }

        private void assetsData(JsonNode node, String path, Scan scan) {
            exactFields(node, path, "snapshotTotalDeposit", "snapshotStockValue", "snapshotFundValue",
                    "snapshotTotalAssets", "liveStockValue", "liveTotalAssets", "targetPriceComplete", "rowCounts",
                    "checks", "depositGroups");
            for (String field : List.of("snapshotTotalDeposit", "snapshotStockValue", "snapshotFundValue",
                    "snapshotTotalAssets", "liveStockValue", "liveTotalAssets")) {
                metric(node.get(field), path + "." + field, TWD, scan);
            }
            bool(node.get("targetPriceComplete"), path + ".targetPriceComplete");
            JsonNode rowCounts = node.get("rowCounts");
            exactFields(rowCounts, path + ".rowCounts", "deposits", "stocks", "funds");
            for (String field : List.of("deposits", "stocks", "funds")) {
                integer(rowCounts.get(field), 0, 1_000_000, path + ".rowCounts." + field);
            }
            JsonNode checks = node.get("checks");
            if (checks == null || !checks.isArray() || checks.size() != 8) fail(path + ".checks 必須恰為 8 項");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < checks.size(); i++) {
                JsonNode check = checks.get(i);
                String cp = path + ".checks[" + i + "]";
                exactFields(check, cp, "name", "detailTwd", "reportedTwd", "differenceTwd", "toleranceTwd", "passed");
                names.add(enumText(check.get("name"), CHECK_NAMES, cp + ".name"));
                for (String field : List.of("detailTwd", "reportedTwd", "differenceTwd", "toleranceTwd")) {
                    decimal(check.get(field), cp + "." + field);
                }
                constBool(check, "passed", true, cp);
            }
            if (!names.equals(CHECK_NAMES)) fail(path + ".checks 名稱不完整或重複");
            JsonNode groups = array(node.get("depositGroups"), path + ".depositGroups");
            String[] previous = null;
            for (int i = 0; i < groups.size(); i++) {
                JsonNode group = groups.get(i);
                String gp = path + ".depositGroups[" + i + "]";
                exactFields(group, gp, "bankId", "bankName", "currency", "depositType", "sourceRowIds", "amountTwd",
                        "originalAmount", "estimatedAnnualInterest");
                String bankId = group.get("bankId").isNull() ? null : id(group.get("bankId"), gp + ".bankId");
                nullableText(group.get("bankName"), gp + ".bankName");
                String currency = text(group.get("currency"), gp + ".currency");
                String depositType = text(group.get("depositType"), gp + ".depositType");
                List<String> rows = idArray(group.get("sourceRowIds"), gp + ".sourceRowIds");
                if (rows.isEmpty()) fail(gp + ".sourceRowIds 至少一項");
                decimal(group.get("amountTwd"), gp + ".amountTwd");
                nullableDecimal(group.get("originalAmount"), gp + ".originalAmount");
                metric(group.get("estimatedAnnualInterest"), gp + ".estimatedAnnualInterest", TWD, scan);
                String[] key = {currency, depositType, bankId};
                if (previous != null && DEPOSIT_GROUP_ORDER.compare(previous, key) >= 0) {
                    fail(path + ".depositGroups 未依 (currency, depositType, bankId) 排序或重複");
                }
                previous = key;
            }
        }

        private void allocationData(JsonNode node, String path, Scan scan) {
            exactFields(node, path, "basis", "denominatorTwd", "shortTermSleeveSeparated", "rows", "unmappedHoldingIds");
            constText(node, "basis", "TOTAL_EXPOSURE_REFERENCE", path);
            metric(node.get("denominatorTwd"), path + ".denominatorTwd", TWD, scan);
            constBool(node, "shortTermSleeveSeparated", false, path);
            JsonNode rows = array(node.get("rows"), path + ".rows");
            String previous = null;
            for (int i = 0; i < rows.size(); i++) {
                JsonNode row = rows.get(i);
                String rp = path + ".rows[" + i + "]";
                exactFields(row, rp, "assetKey", "market", "symbol", "sourceHoldingIds", "exposureTwd",
                        "currentWeight", "targetWeight", "gapWeight", "gapValueTwd");
                String assetKey = id(row.get("assetKey"), rp + ".assetKey");
                if (previous != null && previous.compareTo(assetKey) >= 0) fail(path + ".rows 未依 assetKey 排序或重複");
                previous = assetKey;
                nullableText(row.get("market"), rp + ".market");
                nullableText(row.get("symbol"), rp + ".symbol");
                idArray(row.get("sourceHoldingIds"), rp + ".sourceHoldingIds");
                metric(row.get("exposureTwd"), rp + ".exposureTwd", TWD, scan);
                metric(row.get("currentWeight"), rp + ".currentWeight", RATIO, scan);
                metric(row.get("targetWeight"), rp + ".targetWeight", RATIO, scan);
                metric(row.get("gapWeight"), rp + ".gapWeight", RATIO, scan);
                metric(row.get("gapValueTwd"), rp + ".gapValueTwd", TWD, scan);
            }
            if (!idArray(node.get("unmappedHoldingIds"), path + ".unmappedHoldingIds").isEmpty()) scan.gaps = true;
        }

        private void cashIncomeData(JsonNode node, String path, Scan scan) {
            List<String> metrics = List.of("stockAndEtfDistributions", "fundDistributions", "depositInterest",
                    "sourceAccruedAnnualIncome", "permanentTermInterestReinvested", "spendableAnnualGross",
                    "taiwanIncomeTaxOrRefund", "usWithholding", "additionalBasicTax", "supplementaryNhi",
                    "afterAllTaxAnnualCashIncome");
            List<String> fields = new ArrayList<>(metrics);
            fields.addAll(List.of("missingIncomeRowIds", "netCalculationStandard", "taxYear"));
            exactFields(node, path, fields.toArray(String[]::new));
            for (String field : metrics) {
                metric(node.get(field), path + "." + field, TWD, scan);
            }
            if (!idArray(node.get("missingIncomeRowIds"), path + ".missingIncomeRowIds").isEmpty()) scan.gaps = true;
            nullableText(node.get("netCalculationStandard"), path + ".netCalculationStandard");
            integer(node.get("taxYear"), 2000, 2200, path + ".taxYear");
        }

        private void fundingData(JsonNode node, String path, Scan scan) {
            exactFields(node, path, "basis", "calculationDate", "snapshotAllCurrencyDepositTwdEquivalent",
                    "twdTermDeposits", "twdTotalIncludingNegativeTransit", "excludedPositiveTransit",
                    "usdDepositsTwdEquivalent", "inflationIndex", "permanentTermFloorNominal",
                    "totalTwdDepositFloorNominal", "headroomAboveTotalFloor", "termFloorMet", "tradingAuthorized");
            constText(node, "basis", "BEFORE_PROPOSED_TRADE", path);
            date(node.get("calculationDate"), path + ".calculationDate");
            for (String field : List.of("snapshotAllCurrencyDepositTwdEquivalent", "twdTermDeposits",
                    "twdTotalIncludingNegativeTransit", "excludedPositiveTransit", "usdDepositsTwdEquivalent",
                    "permanentTermFloorNominal", "totalTwdDepositFloorNominal", "headroomAboveTotalFloor")) {
                metric(node.get(field), path + "." + field, TWD, scan);
            }
            metric(node.get("inflationIndex"), path + ".inflationIndex", RATIO, scan);
            JsonNode termFloorMet = node.get("termFloorMet");
            if (!termFloorMet.isNull()) bool(termFloorMet, path + ".termFloorMet");
            constBool(node, "tradingAuthorized", false, path);
        }

        private void technicalsData(JsonNode node, String path, Scan scan) {
            exactFields(node, path, "market", "requiredCompletedSession", "symbolScope", "rows");
            constText(node, "market", "台股", path);
            date(node.get("requiredCompletedSession"), path + ".requiredCompletedSession");
            constText(node, "symbolScope", "TW_HOLDINGS_UNION_POLICY_TARGETS", path);
            JsonNode rows = array(node.get("rows"), path + ".rows");
            String previous = null;
            for (int i = 0; i < rows.size(); i++) {
                JsonNode row = rows.get(i);
                String rp = path + ".rows[" + i + "]";
                exactFields(row, rp, "market", "symbol", "currency", "status", "reasonCodes", "sourceIds",
                        "completedSession", "calculationVersion", "adjustmentBasis", "corporateActionEvidenceComplete",
                        "ma5", "ma20", "ma60", "kdLastThreeCompletedSessions", "adjustedClosesLast20",
                        "highOf20AdjustedCloses");
                constText(row, "market", "台股", rp);
                String symbol = nonEmptyText(row.get("symbol"), rp + ".symbol");
                if (previous != null && previous.compareTo(symbol) >= 0) fail(path + ".rows 未依 market/symbol 排序或重複");
                previous = symbol;
                nonEmptyText(row.get("currency"), rp + ".currency");
                enumText(row.get("status"), MODULE_STATUSES, rp + ".status");
                reasonCodes(row.get("reasonCodes"), rp + ".reasonCodes");
                sourceIds(row.get("sourceIds"), rp + ".sourceIds");
                date(row.get("completedSession"), rp + ".completedSession");
                nonEmptyText(row.get("calculationVersion"), rp + ".calculationVersion");
                enumText(row.get("adjustmentBasis"), Set.of("VERIFIED_CASH_ADJUSTED", "UNVERIFIED"), rp + ".adjustmentBasis");
                bool(row.get("corporateActionEvidenceComplete"), rp + ".corporateActionEvidenceComplete");
                for (String field : List.of("ma5", "ma20", "ma60", "highOf20AdjustedCloses")) {
                    metric(row.get(field), rp + "." + field, NATIVE_PRICE, scan);
                }
                JsonNode kd = array(row.get("kdLastThreeCompletedSessions"), rp + ".kdLastThreeCompletedSessions");
                if (kd.size() > 3) fail(rp + ".kdLastThreeCompletedSessions 最多 3 項");
                LocalDate previousDate = null;
                for (int k = 0; k < kd.size(); k++) {
                    String kp = rp + ".kdLastThreeCompletedSessions[" + k + "]";
                    exactFields(kd.get(k), kp, "date", "k", "d");
                    LocalDate day = date(kd.get(k).get("date"), kp + ".date");
                    if (previousDate != null && !day.isAfter(previousDate)) fail(kp + " 日期須升冪且唯一");
                    previousDate = day;
                    nullableDecimal(kd.get(k).get("k"), kp + ".k");
                    nullableDecimal(kd.get(k).get("d"), kp + ".d");
                }
                JsonNode closes = array(row.get("adjustedClosesLast20"), rp + ".adjustedClosesLast20");
                if (closes.size() > 20) fail(rp + ".adjustedClosesLast20 最多 20 項");
                previousDate = null;
                for (int k = 0; k < closes.size(); k++) {
                    String kp = rp + ".adjustedClosesLast20[" + k + "]";
                    exactFields(closes.get(k), kp, "date", "close");
                    LocalDate day = date(closes.get(k).get("date"), kp + ".date");
                    if (previousDate != null && !day.isAfter(previousDate)) fail(kp + " 日期須升冪且唯一");
                    previousDate = day;
                    decimal(closes.get(k).get("close"), kp + ".close");
                }
            }
        }

        // ---------------------------------------------------------- metric / ids

        private void metric(JsonNode node, String path, String unit, Scan scan) {
            exactFields(node, path, "value", "unit", "quality", "reasonCodes", "sourceIds");
            constText(node, "unit", unit, path);
            String quality = enumText(node.get("quality"), QUALITIES, path + ".quality");
            int reasons = reasonCodes(node.get("reasonCodes"), path + ".reasonCodes");
            List<String> sources = sourceIds(node.get("sourceIds"), path + ".sourceIds");
            if ("UNAVAILABLE".equals(quality)) {
                scan.unavailableMetric = true;
                if (!node.get("value").isNull()) fail(path + ".value UNAVAILABLE 必須為 null");
                if (reasons < 1) fail(path + " UNAVAILABLE 必須有 reasonCodes");
            } else {
                decimal(node.get("value"), path + ".value");
                if (sources.isEmpty()) fail(path + ".sourceIds 至少一項");
            }
        }

        private List<String> sourceIds(JsonNode node, String path) {
            List<String> ids = idArray(node, path);
            for (String id : ids) {
                if (!availableSources.contains(id)) fail(path + " 引用非 AVAILABLE source");
            }
            return ids;
        }
    }

    @FunctionalInterface
    private interface DataValidator {
        void validate(JsonNode node, String path, Scan scan);
    }

    private static final class Scan {
        boolean unavailableMetric;
        boolean gaps;
    }

    /** depositGroups 比較器：逐鍵 {@link String#compareTo}，bankId 以 wire 字串比較、null 排最前。 */
    static final Comparator<String[]> DEPOSIT_GROUP_ORDER = (a, b) -> {
        int c = a[0].compareTo(b[0]);
        if (c != 0) return c;
        c = a[1].compareTo(b[1]);
        if (c != 0) return c;
        return Comparator.<String>nullsFirst(Comparator.naturalOrder()).compare(a[2], b[2]);
    };

    // ---------------------------------------------------------------- primitives

    private static void exactFields(JsonNode node, String path, String... names) {
        if (node == null || !node.isObject()) fail(path + " 必須是 object");
        if (node.size() != names.length) fail(path + " 欄位數不符");
        for (String name : names) {
            if (!node.has(name)) fail(path + " 缺欄位 " + name);
        }
    }

    private static JsonNode array(JsonNode node, String path) {
        if (node == null || !node.isArray()) fail(path + " 必須是 array");
        return node;
    }

    private static String text(JsonNode node, String path) {
        if (node == null || !node.isTextual()) fail(path + " 必須是 string");
        return node.textValue();
    }

    private static String nonEmptyText(JsonNode node, String path) {
        String value = text(node, path);
        if (value.isEmpty()) fail(path + " 不得為空字串");
        return value;
    }

    private static void nullableText(JsonNode node, String path) {
        if (node == null || !(node.isNull() || node.isTextual())) fail(path + " 必須是 string 或 null");
    }

    private static void constText(JsonNode parent, String field, String expected, String path) {
        if (!expected.equals(text(parent.get(field), path + "." + field))) fail(path + "." + field + " 常數不符");
    }

    private static String enumText(JsonNode node, Set<String> allowed, String path) {
        String value = text(node, path);
        if (!allowed.contains(value)) fail(path + " enum 不合法");
        return value;
    }

    private static boolean bool(JsonNode node, String path) {
        if (node == null || !node.isBoolean()) fail(path + " 必須是 boolean");
        return node.booleanValue();
    }

    private static void constBool(JsonNode parent, String field, boolean expected, String path) {
        if (bool(parent.get(field), path + "." + field) != expected) fail(path + "." + field + " 常數不符");
    }

    private static void integer(JsonNode node, long min, long max, String path) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) fail(path + " 必須是整數");
        long value = node.longValue();
        if (value < min || value > max) fail(path + " 超出範圍");
    }

    private static String id(JsonNode node, String path) {
        String value = text(node, path);
        if (value.isEmpty() || value.length() > 128) fail(path + " 長度須 1–128");
        return value;
    }

    private static String uuid(JsonNode node, String path) {
        String value = text(node, path);
        if (!UUID.matcher(value).matches()) fail(path + " 必須是小寫 UUID");
        return value;
    }

    private static String sha(JsonNode node, String path) {
        String value = text(node, path);
        if (!SHA256.matcher(value).matches()) fail(path + " 必須是 64 位小寫 hex");
        return value;
    }

    static boolean isCanonicalDecimal(String value) {
        return value != null && value.length() <= 80 && !"-0".equals(value) && DECIMAL.matcher(value).matches();
    }

    private static void decimal(JsonNode node, String path) {
        if (node == null || !node.isTextual() || !isCanonicalDecimal(node.textValue())) {
            fail(path + " 必須是 canonical Decimal 字串");
        }
    }

    private static void nullableDecimal(JsonNode node, String path) {
        if (node != null && node.isNull()) return;
        decimal(node, path);
    }

    private static LocalDate date(JsonNode node, String path) {
        String value = text(node, path);
        if (!DATE.matcher(value).matches()) fail(path + " 必須是 yyyy-MM-dd");
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            throw new SrppPayloadException(path + " 不是合法日期", ex);
        }
    }

    private static Instant dateTime(JsonNode node, String path) {
        String value = text(node, path);
        if (!DATE_TIME.matcher(value).matches()) fail(path + " 必須是含時區的 RFC 3339 時間");
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            throw new SrppPayloadException(path + " 不是合法時間", ex);
        }
    }

    private static int reasonCodes(JsonNode node, String path) {
        array(node, path);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String code = text(node.get(i), path + "[" + i + "]");
            if (!REASON_CODE.matcher(code).matches()) fail(path + "[" + i + "] 格式不合法");
            if (!seen.add(code)) fail(path + " 重複");
        }
        return node.size();
    }

    /** ID 陣列：每項 SrppId，且依 {@link String#compareTo} 嚴格遞增（兼驗唯一）。 */
    private static List<String> idArray(JsonNode node, String path) {
        array(node, path);
        List<String> ids = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            String value = id(node.get(i), path + "[" + i + "]");
            if (!ids.isEmpty() && ids.get(ids.size() - 1).compareTo(value) >= 0) fail(path + " 未依字串序排序或重複");
            ids.add(value);
        }
        return ids;
    }

    private static void fail(String message) {
        throw new SrppPayloadException(Objects.requireNonNull(message));
    }
}
