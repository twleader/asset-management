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
    public static final String MANIFEST_JSON_V3 = "{\"calculations\":{\"allocation\":{\"assetKey\":\"ASSET_KEY_V1\",\"calculationId\":\"ASSET_MGMT_TOTAL_EXPOSURE_V1\",\"division\":\"MathContext(34,HALF_EVEN)\"},\"assets\":{\"calculationId\":\"ASSET_MGMT_ASSETS_RECON_V2\",\"dataAsOf\":\"MIN_LIVE_STOCK_UPDATED_AT_V1\",\"depositInterest\":\"SnapshotAggregateCalculator.depositEstimatedInterest\",\"depositNullRatePolicy\":\"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2\",\"inputs\":\"LatestAssetsDto.Response(snapshot,liveAssets,targetPriceComplete)\",\"revisionProjection\":\"SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1\",\"tolerance\":\"max(0.01,max(abs(detail),abs(reported))*0.0001)\"},\"cashIncome\":{\"calculationId\":\"ASSET_MGMT_GROSS_INCOME_V2\",\"depositNullRatePolicy\":\"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2\",\"netCalculation\":\"NOT_VERIFIED\"},\"completedTechnicals\":{\"calculationId\":\"ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1\",\"status\":\"CALCULATOR_NOT_VERIFIED\"},\"dividendStabilization\":{\"calculationId\":\"DIVIDEND_TOPUP_D218_COMPLETED_TWO_SESSIONS_FIXED_WAVE_CUMULATIVE_BUDGET\",\"sourceSha256\":{\"SRPP/scripts/dividend_stabilization.py\":\"2a5c47e261eb83d560ace9a37591d5e958423e0447ec9c9dd896823972a19f5e\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppDividendStabilization.java\":\"d3fe87bab027b27f9ac52d420bf7e3a76d90120983a9bae802bc6749afd1a591\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java\":\"6294a1daf9ca3344b6ff3438d7f720901eaa653f7e677ae282ae2071c08438fa\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportTopupSizing.java\":\"18ad0280cfe15efc48afc198df01910c3ac810cf48c175b1df56418bd6d50748\"}},\"funding\":{\"calculationId\":\"ASSET_MGMT_FUNDING_UNAVAILABLE_V1\",\"status\":\"CALCULATOR_NOT_VERIFIED\"},\"namedCashRotation\":{\"calculationId\":\"NAMED_CASH_ROTATION_D223\",\"sourceSha256\":{\"SRPP/scripts/execution_policy.py\":\"16fb30de344493be599d89541f0f76ee985bcd64996dada819bf0f701aad4a2d\",\"SRPP/scripts/named_cash_rotation.py\":\"ead695446e367c08249b68cfa1813f62a49746a4b7b34a4ae13369e379a97745\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppNamedCashBuyBudget.java\":\"777d85c1fe09f5212e8fde5240b8f7e65be6ddf1269f62d56d75e2c7b556a2d8\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppNamedRadarSellCalculator.java\":\"71ee7a5f29398703c3048eab0596c27d4dcb63b9835cbac1361aabb7b58d897c\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java\":\"6294a1daf9ca3344b6ff3438d7f720901eaa653f7e677ae282ae2071c08438fa\",\"asset-management/backend/src/main/java/com/steven/assets/srpp/SrppNamedCashRotation.java\":\"d5be5b0246819c31c4a6086104e550e507ebe73a52fce7e13fb3ba4aecab3dc1\"}},\"reserveAllocation\":{\"calculationId\":\"REAL_TWD_RESERVE_ALLOCATION_D222\",\"sourceSha256\":{\"SRPP/scripts/reserve_allocation.py\":\"7a20e73868f804786eface316325aa98e3793496550d0c02897b2d576995f6d0\",\"asset-management/backend/src/main/java/com/steven/assets/service/DistributionAdjustedPriceService.java\":\"24b90eb9f7dabcafdf10d842b10f2669a3b76d39d489ec354bbf228d0ce8e16a\",\"asset-management/backend/src/main/java/com/steven/assets/service/RadarCompletedPriceDrawdown.java\":\"c4a97f3ad00fee97de0f192d8e271a29f525d677bf79b1d9d392c687e3e99def\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportExtraCaptureAdapter.java\":\"7d90bd899e79e0f6967ed77564d5bb368014ee3074a3e637744084b5562da590\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportMediumDipCalculator.java\":\"2c51d54a3101a81fb3d3650ec498ee393c09e2cd01eb4efce131e10679699ffc\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java\":\"6294a1daf9ca3344b6ff3438d7f720901eaa653f7e677ae282ae2071c08438fa\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportShortUsCalculator.java\":\"96ebd1fc56e0979960963c1a6a84575394709a644cd8ed5e12c12f19dd24cfdf\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportStateCalculator.java\":\"9fa1dbe181420f2464f4763a7ee995143e10f5c2d6107d42af9304a3ed9fc36f\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/us/UsCoreSellCalculator.java\":\"953cca8a65af8c97572d0ce36a09909d83c6650f4658477ed091615e9ae6914e\",\"asset-management/backend/src/main/java/com/steven/assets/srpp/SrppModuleCalculator.java\":\"ebd81af943104f9fda9a7877af67556ed70301091ace7adc0793041eb0f270f7\",\"asset-management/backend/src/main/java/com/steven/assets/srpp/SrppPolicyDocument.java\":\"b05e62855bc1f6c4680196fabee67263f072ee49fe25bf6c644aefbcfae002be\",\"asset-management/backend/src/main/java/com/steven/assets/srpp/SrppReserveAllocation.java\":\"bc565a044d25e8816bbb91bb038d713c1d9eb5ba705dbbf03d8ee50f420e0611\"}},\"satelliteBuild\":{\"calculationId\":\"GLOBAL_EQUITY_SATELLITE_BUILD_D143_D234\",\"sourceSha256\":{\"SRPP/scripts/execution_policy.py\":\"16fb30de344493be599d89541f0f76ee985bcd64996dada819bf0f701aad4a2d\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java\":\"6294a1daf9ca3344b6ff3438d7f720901eaa653f7e677ae282ae2071c08438fa\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportProducerScheduler.java\":\"23b66e341ba21fd4869d5c353a354dc7f515c94ee7b03658bba48e5134cee098\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppSatelliteBuildCalculator.java\":\"0d3cc566e1cc86679fd8c1014c1a1839ebfff652fc0993655f109fb96ffa45e5\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppSatelliteBuildCapture.java\":\"ddf359d20f8cf75fca18d523173be6cf1dac9e35ea729a746b774c64e7641e66\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppSatelliteBuildPolicy.java\":\"9e808b22ff5a29a7fb7b786c44a390f5b6e9a241bd8ac44cd41a1ab492941f00\"}},\"zeroTargetHighZoneExit\":{\"calculationId\":\"ZERO_TARGET_HIGH_ZONE_EXIT_D236\",\"sourceSha256\":{\"SRPP/scripts/correction_dip.py\":\"1b94ffa30ed6f1a24974f1781c73147a8da52dd5ed51f03f199e18ebdff657f4\",\"SRPP/scripts/execution_policy.py\":\"16fb30de344493be599d89541f0f76ee985bcd64996dada819bf0f701aad4a2d\",\"SRPP/scripts/reserve_allocation.py\":\"7a20e73868f804786eface316325aa98e3793496550d0c02897b2d576995f6d0\",\"SRPP/scripts/zero_target_high_zone_exit.py\":\"df834bdf5abcf2096877c0f91c6509f6bac3636aa52fe00873fcef90587f19bb\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppNamedRadarSellCalculator.java\":\"71ee7a5f29398703c3048eab0596c27d4dcb63b9835cbac1361aabb7b58d897c\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportExtraCaptureAdapter.java\":\"7d90bd899e79e0f6967ed77564d5bb368014ee3074a3e637744084b5562da590\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java\":\"6294a1daf9ca3344b6ff3438d7f720901eaa653f7e677ae282ae2071c08438fa\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppZeroTargetExitContext.java\":\"9ebc9d95a2643434e155682c071048d49eb4d0ddad62ae670ce6c79ec6000999\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppZeroTargetHighZoneExitCalculator.java\":\"79102d7a165e87340529f08edddfe42872bdb2d491f74f70d6bc21c678e71864\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppZeroTargetPortfolioFloor.java\":\"bf06c4d8cf1ae79e9523266e3887f7953221ded48bb5dc66098d6edf1f2bfc92\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/report/SrppZeroTargetProtection.java\":\"69ea64eeb8c6af90006f03be73a74c20dd7a2827eddba47ed8336ebbf798ddbf\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/us/UsCoreSellCalculator.java\":\"953cca8a65af8c97572d0ce36a09909d83c6650f4658477ed091615e9ae6914e\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/us/UsReportCalendar.java\":\"c26ca39e404047a366e8e26b46153fe81c565b9664fb833dc33aff8493d6d13e\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/us/UsReportCaptureAdapter.java\":\"f06bab38e3b5a45613268f68e12a7db2a11f595265540cd8a2facef7867f6283\",\"asset-management/backend/src/main/java/com/steven/assets/service/srpp/us/UsReportPublisher.java\":\"ec43d5501335c0d468e56c5bc6f87efbba5fd6e045eb5d02587c7fc8a5093a89\",\"asset-management/backend/src/main/java/com/steven/assets/srpp/SrppReserveAllocation.java\":\"bc565a044d25e8816bbb91bb038d713c1d9eb5ba705dbbf03d8ee50f420e0611\",\"asset-management/bff/src/main/java/com/steven/assets/bff/publicsrpp/SrppCompletionReserveAllocation.java\":\"be8d96b9eff160460a8d586a9bd77fdd4be79f09431f0c0376e80aa823fe47b9\",\"asset-management/bff/src/main/java/com/steven/assets/bff/publicsrpp/SrppZeroTargetExitValidator.java\":\"f67993468f62ce92ba61b7bda3daecf0ade6b2ff7c90cc40632c581429e51e8c\"}}},\"decimal\":\"canonical-string\",\"formulaVersion\":\"ASSET_MGMT_SRPP_V3\",\"hash\":\"RFC8785-JCS+SHA-256\",\"offsetLessTimezone\":\"Asia/Taipei\",\"schema\":\"SRPP_FORMULA_MANIFEST_V3\",\"timezone\":\"Asia/Taipei\"}";
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
