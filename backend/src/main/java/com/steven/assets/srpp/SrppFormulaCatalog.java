package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Requirement 163／Task 452.4、476、588：歷史 V1/V2 與來源綁定 V3 manifest。
 *
 * <p>兩版 manifest 都固定在此；V1 常數與 digest 保留供舊 package 重播，V2 與已審核 fixture
 * 逐位元比對。registry 依版本做 JCS 精確比對。
 */
public final class SrppFormulaCatalog {
    private SrppFormulaCatalog() {}

    public static final String FORMULA_VERSION = "ASSET_MGMT_SRPP_V1";
    public static final String FORMULA_VERSION_V2 = "ASSET_MGMT_SRPP_V2";
    public static final String FORMULA_VERSION_V3 = "ASSET_MGMT_SRPP_V3";
    public static final String CURRENT_FORMULA_VERSION = FORMULA_VERSION_V3;

    public static final String CALC_ASSETS = "ASSET_MGMT_ASSETS_RECON_V1";
    public static final String CALC_ALLOCATION = "ASSET_MGMT_TOTAL_EXPOSURE_V1";
    public static final String CALC_CASH_INCOME = "ASSET_MGMT_GROSS_INCOME_V1";
    public static final String CALC_ASSETS_V2 = "ASSET_MGMT_ASSETS_RECON_V2";
    public static final String CALC_CASH_INCOME_V2 = "ASSET_MGMT_GROSS_INCOME_V2";
    public static final String CALC_FUNDING = "ASSET_MGMT_FUNDING_UNAVAILABLE_V1";
    public static final String CALC_TECHNICALS = "ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1";

    public static final String MANIFEST_JSON = "{\"schema\":\"SRPP_FORMULA_MANIFEST_V1\",\"formulaVersion\":\"ASSET_MGMT_SRPP_V1\","
            + "\"calculations\":{"
            + "\"assets\":{\"calculationId\":\"ASSET_MGMT_ASSETS_RECON_V1\","
            + "\"inputs\":\"LatestAssetsDto.Response(snapshot,liveAssets,targetPriceComplete)\","
            + "\"revisionProjection\":\"SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1\","
            + "\"dataAsOf\":\"MIN_LIVE_STOCK_UPDATED_AT_V1\","
            + "\"tolerance\":\"max(0.01,max(abs(detail),abs(reported))*0.0001)\","
            + "\"depositInterest\":\"SnapshotAggregateCalculator.depositEstimatedInterest\"},"
            + "\"allocation\":{\"calculationId\":\"ASSET_MGMT_TOTAL_EXPOSURE_V1\",\"assetKey\":\"ASSET_KEY_V1\","
            + "\"division\":\"MathContext(34,HALF_EVEN)\"},"
            + "\"cashIncome\":{\"calculationId\":\"ASSET_MGMT_GROSS_INCOME_V1\",\"netCalculation\":\"NOT_VERIFIED\"},"
            + "\"funding\":{\"calculationId\":\"ASSET_MGMT_FUNDING_UNAVAILABLE_V1\",\"status\":\"CALCULATOR_NOT_VERIFIED\"},"
            + "\"completedTechnicals\":{\"calculationId\":\"ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1\","
            + "\"status\":\"CALCULATOR_NOT_VERIFIED\"}},"
            + "\"timezone\":\"Asia/Taipei\",\"offsetLessTimezone\":\"Asia/Taipei\",\"decimal\":\"canonical-string\","
            + "\"hash\":\"RFC8785-JCS+SHA-256\"}";

    // 與 spec/fixtures/srpp_formula_manifest_v2.json 的 JCS 位元組及 golden digest 逐位元比對。
    public static final String MANIFEST_JSON_V2 = "{\"calculations\":{\"allocation\":{\"assetKey\":\"ASSET_KEY_V1\",\"calculationId\":\"ASSET_MGMT_TOTAL_EXPOSURE_V1\",\"division\":\"MathContext(34,HALF_EVEN)\"},\"assets\":{\"calculationId\":\"ASSET_MGMT_ASSETS_RECON_V2\",\"dataAsOf\":\"MIN_LIVE_STOCK_UPDATED_AT_V1\",\"depositInterest\":\"SnapshotAggregateCalculator.depositEstimatedInterest\",\"depositNullRatePolicy\":\"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2\",\"inputs\":\"LatestAssetsDto.Response(snapshot,liveAssets,targetPriceComplete)\",\"revisionProjection\":\"SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1\",\"tolerance\":\"max(0.01,max(abs(detail),abs(reported))*0.0001)\"},\"cashIncome\":{\"calculationId\":\"ASSET_MGMT_GROSS_INCOME_V2\",\"depositNullRatePolicy\":\"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2\",\"netCalculation\":\"NOT_VERIFIED\"},\"completedTechnicals\":{\"calculationId\":\"ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1\",\"status\":\"CALCULATOR_NOT_VERIFIED\"},\"funding\":{\"calculationId\":\"ASSET_MGMT_FUNDING_UNAVAILABLE_V1\",\"status\":\"CALCULATOR_NOT_VERIFIED\"}},\"decimal\":\"canonical-string\",\"formulaVersion\":\"ASSET_MGMT_SRPP_V2\",\"hash\":\"RFC8785-JCS+SHA-256\",\"offsetLessTimezone\":\"Asia/Taipei\",\"schema\":\"SRPP_FORMULA_MANIFEST_V2\",\"timezone\":\"Asia/Taipei\"}";

    // BEGIN GENERATED D218 FORMULA V3
    public static final String MANIFEST_JSON_V3 = "{\"calculations\":{\"allocation\":{\"assetKey\":\"ASSET_KEY_V1\",\"calculationId\":\"ASSET_MGMT_TOTAL_EXPOSURE_V1\",\"division\":\"MathContext(34,HALF_EVEN)\"},\"assets\":{\"calculationId\":\"ASSET_MGMT_ASSETS_RECON_V2\",\"dataAsOf\":\"MIN_LIVE_STOCK_UPDATED_AT_V1\",\"depositInterest\":\"SnapshotAggregateCalculator.depositEstimatedInterest\",\"depositNullRatePolicy\":\"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2\",\"inputs\":\"LatestAssetsDto.Response(snapshot,liveAssets,targetPriceComplete)\",\"revisionProjection\":\"SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1\",\"tolerance\":\"max(0.01,max(abs(detail),abs(reported))*0.0001)\"},\"cashIncome\":{\"calculationId\":\"ASSET_MGMT_GROSS_INCOME_V2\",\"depositNullRatePolicy\":\"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2\",\"netCalculation\":\"NOT_VERIFIED\"},\"completedTechnicals\":{\"calculationId\":\"ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1\",\"status\":\"CALCULATOR_NOT_VERIFIED\"},\"dividendStabilization\":{\"calculationId\":\"DIVIDEND_TOPUP_D218_COMPLETED_TWO_SESSIONS_FIXED_WAVE_CUMULATIVE_BUDGET\",\"sourceSha256\":{\"SRPP/scripts/dividend_stabilization.py\":\"2a5c47e261eb83d560ace9a37591d5e958423e0447ec9c9dd896823972a19f5e\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppDividendStabilization.java\":\"d3fe87bab027b27f9ac52d420bf7e3a76d90120983a9bae802bc6749afd1a591\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java\":\"c318166e411aa00922a5754284137cdf39f2a307538f6fbd0f7de7d7ea9428ac\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportTopupSizing.java\":\"d150a37d072b519990b3a7da91cfc9b2bee3804adee1b194f29aebb92ac3f4f8\"}},\"funding\":{\"calculationId\":\"ASSET_MGMT_FUNDING_UNAVAILABLE_V1\",\"status\":\"CALCULATOR_NOT_VERIFIED\"}},\"decimal\":\"canonical-string\",\"formulaVersion\":\"ASSET_MGMT_SRPP_V3\",\"hash\":\"RFC8785-JCS+SHA-256\",\"offsetLessTimezone\":\"Asia/Taipei\",\"schema\":\"SRPP_FORMULA_MANIFEST_V3\",\"timezone\":\"Asia/Taipei\"}";
    // END GENERATED D218 FORMULA V3

    private static final JsonNode MANIFEST = SrppJcs.parseStrict(MANIFEST_JSON);
    private static final String MANIFEST_JCS = SrppJcs.canonicalize(MANIFEST);
    private static final String FORMULA_SET_SHA256 = SrppJcs.sha256Hex(MANIFEST_JCS);
    private static final JsonNode MANIFEST_V2 = SrppJcs.parseStrict(MANIFEST_JSON_V2);
    private static final String MANIFEST_JCS_V2 = SrppJcs.canonicalize(MANIFEST_V2);
    private static final String FORMULA_SET_SHA256_V2 = SrppJcs.sha256Hex(MANIFEST_JCS_V2);
    private static final JsonNode MANIFEST_V3 = SrppJcs.parseStrict(MANIFEST_JSON_V3);
    private static final String MANIFEST_JCS_V3 = SrppJcs.canonicalize(MANIFEST_V3);
    private static final String FORMULA_SET_SHA256_V3 = SrppJcs.sha256Hex(MANIFEST_JCS_V3);

    /** 回傳 manifest 的深拷貝，呼叫端不得改動常數內容。 */
    public static JsonNode manifest() {
        return MANIFEST.deepCopy();
    }

    public static String manifestJcs() {
        return MANIFEST_JCS;
    }

    public static String formulaSetSha256() {
        return FORMULA_SET_SHA256;
    }

    public static boolean supported(String version) {
        return FORMULA_VERSION.equals(version) || supportsNullableDepositRates(version);
    }

    /** V3 inherits the reviewed V2 nullable-rate arithmetic without relabelling historical V1. */
    public static boolean supportsNullableDepositRates(String version) {
        return FORMULA_VERSION_V2.equals(version) || FORMULA_VERSION_V3.equals(version);
    }

    public static JsonNode manifest(String version) {
        requireSupported(version);
        if (FORMULA_VERSION_V3.equals(version)) return MANIFEST_V3.deepCopy();
        return FORMULA_VERSION_V2.equals(version) ? MANIFEST_V2.deepCopy() : manifest();
    }

    public static String manifestJcs(String version) {
        requireSupported(version);
        if (FORMULA_VERSION_V3.equals(version)) return MANIFEST_JCS_V3;
        return FORMULA_VERSION_V2.equals(version) ? MANIFEST_JCS_V2 : manifestJcs();
    }

    public static String formulaSetSha256(String version) {
        requireSupported(version);
        if (FORMULA_VERSION_V3.equals(version)) return FORMULA_SET_SHA256_V3;
        return FORMULA_VERSION_V2.equals(version) ? FORMULA_SET_SHA256_V2 : formulaSetSha256();
    }

    private static void requireSupported(String version) {
        if (!supported(version)) throw new IllegalArgumentException("Unsupported SRPP formula version");
    }
}
