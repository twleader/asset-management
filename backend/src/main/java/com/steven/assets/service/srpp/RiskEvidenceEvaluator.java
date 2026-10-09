package com.steven.assets.service.srpp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Requirement 181／Task 481.6：六項國際政經風險的確定性計分（純函式；無 IO、無 Spring 依賴）。
 *
 * <p>每類由「已驗證來源數」與呼叫端判讀的 {@code claimedScore} 決定（規則與門檻見 {@link SrppRiskRubricV1}）：
 * <ul>
 *   <li>無已驗證來源，或 {@code conflicting=true}（此時 {@code claimedScore} 必須為 0）→ 0 分、未評、{@code EVIDENCE_INSUFFICIENT}；</li>
 *   <li>有已驗證來源且 {@code claimedScore=0} → 0 分、已評、{@code VERIFIED_NO_RUBRIC_EVENT}；</li>
 *   <li>有已驗證來源且 {@code claimedScore≥1} → 該分數、已評、{@code RUBRIC_EVENT_FOUND}。</li>
 * </ul>
 * 「已驗證來源」包含 {@code DB_VERIFIED} 與 {@code ATTESTED}；本類不區分兩者，也不判斷來源與類別的相關性。
 * 結果欄位只由本類產生，request 不得自帶。
 */
public final class RiskEvidenceEvaluator {
    private RiskEvidenceEvaluator() {}

    /** 單一類別的證據狀態（協定列舉）。 */
    public enum EvidenceStatus { EVIDENCE_INSUFFICIENT, VERIFIED_NO_RUBRIC_EVENT, RUBRIC_EVENT_FOUND }

    /** 風險模式（D-169）。 */
    public enum RiskMode { NORMAL, CAUTIOUS, DEFENSIVE }

    /** 評分輸入：類別代碼、呼叫端判讀分數、是否來源矛盾、已驗證來源數。 */
    public record CategoryInput(String code, int claimedScore, boolean conflicting, int verifiedSourceCount) {}

    public record CategoryResult(String code, int score, boolean assessed, EvidenceStatus evidenceStatus) {}

    /** {@code categories} 固定依 {@link SrppRiskRubricV1#CATEGORY_CODES} 順序。 */
    public record Assessment(List<CategoryResult> categories, int totalScore, int assessedCount, RiskMode riskMode) {}

    /**
     * {@code SCORE_WITHOUT_EVIDENCE} 判定：{@code claimedScore} 非 0，但沒有任何已驗證來源或 {@code conflicting=true}。
     * 服務層據此收集 422 錯誤；{@link #evaluate} 遇到此情形直接拒絕。
     */
    public static boolean scoreWithoutEvidence(int claimedScore, boolean conflicting, int verifiedSourceCount) {
        return claimedScore != 0 && (verifiedSourceCount <= 0 || conflicting);
    }

    /**
     * 對六類恰好各一筆的輸入計分（順序不拘，輸出依 rubric 順序）。輸入違反 rubric（未知、重複或缺少類別、
     * 分數超出 0 至上限、負的來源數、{@link #scoreWithoutEvidence}）丟 {@link IllegalArgumentException}——
     * 這些情形必須先由服務層以 422 擋下，不可能抵達此處。
     */
    public static Assessment evaluate(List<CategoryInput> inputs) {
        if (inputs == null || inputs.size() != SrppRiskRubricV1.CATEGORY_CODES.size()) {
            throw new IllegalArgumentException("RISK_CATEGORY_COUNT");
        }
        Map<String, CategoryInput> byCode = new HashMap<>();
        for (CategoryInput input : inputs) {
            if (input == null || !SrppRiskRubricV1.known(input.code())) throw new IllegalArgumentException("UNKNOWN_CATEGORY");
            if (byCode.put(input.code(), input) != null) throw new IllegalArgumentException("DUPLICATE_CATEGORY");
            if (input.claimedScore() < 0 || input.claimedScore() > SrppRiskRubricV1.max(input.code())) {
                throw new IllegalArgumentException("SCORE_OUT_OF_RANGE");
            }
            if (input.verifiedSourceCount() < 0) throw new IllegalArgumentException("SOURCE_COUNT_NEGATIVE");
            if (scoreWithoutEvidence(input.claimedScore(), input.conflicting(), input.verifiedSourceCount())) {
                throw new IllegalArgumentException("SCORE_WITHOUT_EVIDENCE");
            }
        }
        List<CategoryResult> results = new ArrayList<>();
        int[] scores = new int[SrppRiskRubricV1.CATEGORY_CODES.size()];
        int total = 0;
        int assessedCount = 0;
        for (int i = 0; i < SrppRiskRubricV1.CATEGORY_CODES.size(); i++) {
            CategoryInput input = byCode.get(SrppRiskRubricV1.CATEGORY_CODES.get(i));
            CategoryResult result = category(input);
            results.add(result);
            scores[i] = result.score();
            total += result.score();
            if (result.assessed()) assessedCount++;
        }
        return new Assessment(List.copyOf(results), total, assessedCount, classify(scores, assessedCount));
    }

    private static CategoryResult category(CategoryInput input) {
        if (input.verifiedSourceCount() == 0 || input.conflicting()) {
            return new CategoryResult(input.code(), 0, false, EvidenceStatus.EVIDENCE_INSUFFICIENT);
        }
        if (input.claimedScore() == 0) {
            return new CategoryResult(input.code(), 0, true, EvidenceStatus.VERIFIED_NO_RUBRIC_EVENT);
        }
        return new CategoryResult(input.code(), input.claimedScore(), true, EvidenceStatus.RUBRIC_EVENT_FOUND);
    }

    /**
     * 風險模式：任一類 {@code score ≥ 3}、或總分 {@code ≥ 8}、或已評數 {@code < 4} → DEFENSIVE；否則總分 {@code ≥ 5}
     * → CAUTIOUS；其餘 NORMAL。package-private 供窮舉測試直接呼叫（經 {@link #evaluate} 造不出「有分數但已評數不足」）。
     */
    static RiskMode classify(int[] scores, int assessedCount) {
        if (scores == null || scores.length != SrppRiskRubricV1.CATEGORY_CODES.size()) {
            throw new IllegalArgumentException("RISK_SCORE_VECTOR");
        }
        if (assessedCount < 0 || assessedCount > scores.length) throw new IllegalArgumentException("ASSESSED_COUNT");
        int total = 0;
        boolean categoryDefensive = false;
        for (int score : scores) {
            if (score < 0) throw new IllegalArgumentException("NEGATIVE_SCORE");
            total += score;
            if (score >= SrppRiskRubricV1.DEFENSIVE_CATEGORY_SCORE) categoryDefensive = true;
        }
        if (categoryDefensive || total >= SrppRiskRubricV1.DEFENSIVE_TOTAL_SCORE
                || assessedCount < SrppRiskRubricV1.MINIMUM_EVALUATED_CATEGORIES) {
            return RiskMode.DEFENSIVE;
        }
        return total >= SrppRiskRubricV1.CAUTIOUS_TOTAL_SCORE ? RiskMode.CAUTIOUS : RiskMode.NORMAL;
    }
}
