package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.srpp.SrppJcs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Requirement 181／Task 481.6：SRPP 六項國際政經風險的計分 rubric（版本 V1）。
 *
 * <p>數值取自 SRPP 專案 {@code assumptions/model_inputs.json} 的 {@code execution_policy.risk_score_category_maxima}
 * 與 {@code risk_score_decision_policy}（SRPP 規則包 D-198／policy v2.46.0 的 D-167／D-168／D-169）。這些是
 * <b>協定列舉與由 SRPP 政策持有的版本化常數</b>，不是使用者可維護的業務分類，因此不入資料庫、不開
 * {@code /api/settings/*}（先例：{@code SrppFormulaCatalog}）。SRPP 規則改變時必須新增 {@code SrppRiskRubricV2}
 * 並以新的 {@link #rubricSha256()} 揭露，<b>不得就地修改本類</b>。
 *
 * <p>{@link #rubricSha256()}＝下列 JSON 經 RFC 8785 JCS（{@link SrppJcs#canonicalize}）後的 UTF-8 SHA-256：
 * {@code {"schema":"SRPP_RISK_RUBRIC_V1","categories":[{"code":…,"max":…},…],"decision":{…}}}；隨回應揭露，
 * 不存資料庫欄位。
 */
public final class SrppRiskRubricV1 {
    private SrppRiskRubricV1() {}

    public static final String SCHEMA = "SRPP_RISK_RUBRIC_V1";

    /** 類別代碼的固定順序（回應 {@code riskAssessment.categories} 依此排列）與各類分數上限。 */
    private static final Map<String, Integer> MAXIMA;
    static {
        Map<String, Integer> maxima = new LinkedHashMap<>();
        maxima.put("fed", 2);
        maxima.put("geopolitics", 3);
        maxima.put("oil", 2);
        maxima.put("taiwan_politics", 3);
        maxima.put("us_taiwan_inflation", 2);
        maxima.put("semiconductor_cycle_and_advanced_process", 3);
        MAXIMA = java.util.Collections.unmodifiableMap(maxima);
    }

    public static final List<String> CATEGORY_CODES = List.copyOf(MAXIMA.keySet());

    /** 任一類分數 ≥ 此值即 DEFENSIVE。 */
    public static final int DEFENSIVE_CATEGORY_SCORE = 3;
    /** 總分 ≥ 此值即 DEFENSIVE。 */
    public static final int DEFENSIVE_TOTAL_SCORE = 8;
    /** 已評類別數 &lt; 此值即 DEFENSIVE。 */
    public static final int MINIMUM_EVALUATED_CATEGORIES = 4;
    /** 非 DEFENSIVE 時總分 ≥ 此值即 CAUTIOUS。 */
    public static final int CAUTIOUS_TOTAL_SCORE = 5;

    private static final String RUBRIC_SHA256 = SrppJcs.hash(document());

    public static boolean known(String code) {
        return code != null && MAXIMA.containsKey(code);
    }

    /** 該類分數上限；未知代碼丟 {@link IllegalArgumentException}。 */
    public static int max(String code) {
        Integer max = code == null ? null : MAXIMA.get(code);
        if (max == null) throw new IllegalArgumentException("UNKNOWN_RISK_CATEGORY");
        return max;
    }

    public static String rubricSha256() {
        return RUBRIC_SHA256;
    }

    /** 計算 {@link #rubricSha256()} 的 rubric 文件（categories 依固定順序）。 */
    static ObjectNode document() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode root = json.objectNode();
        root.put("schema", SCHEMA);
        ArrayNode categories = root.putArray("categories");
        MAXIMA.forEach((code, max) -> categories.addObject().put("code", code).put("max", max));
        ObjectNode decision = root.putObject("decision");
        decision.put("cautiousTotalScore", CAUTIOUS_TOTAL_SCORE);
        decision.put("defensiveCategoryScore", DEFENSIVE_CATEGORY_SCORE);
        decision.put("defensiveTotalScore", DEFENSIVE_TOTAL_SCORE);
        decision.put("minimumEvaluatedCategories", MINIMUM_EVALUATED_CATEGORIES);
        return root;
    }
}
