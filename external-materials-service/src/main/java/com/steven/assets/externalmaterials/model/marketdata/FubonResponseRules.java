package com.steven.assets.externalmaterials.model.marketdata;

/** Pure validation shared by orchestration and persistence adapters. */
public final class FubonResponseRules {
    private FubonResponseRules() {}
    private static final String TAIWAN = "台股";
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^[0-9]{4,6}[A-Z]?$");
    public static boolean supportedIdentity(String code, String market) {
        return TAIWAN.equals(market) && code != null && CODE.matcher(code).matches() && !"0000".equals(code);
    }
}
