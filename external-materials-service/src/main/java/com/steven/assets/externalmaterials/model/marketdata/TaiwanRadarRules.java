package com.steven.assets.externalmaterials.model.marketdata;

/** Pure validation shared by orchestration and persistence adapters. */
public final class TaiwanRadarRules {
    private TaiwanRadarRules() {}
    /** 與台股雷達 SQL 的正規表達式完全相同，避免下游 Fubon consumer 擴張主檔錨定後的範圍。 */
    public static boolean isTaiwanRadarCode(String code) {
        return code != null && code.matches("^[0-9]{4,6}[A-Z]?$") && !"0000".equals(code);
    }
}
