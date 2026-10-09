package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.service.srpp.RiskEvidenceEvaluator.Assessment;
import com.steven.assets.service.srpp.RiskEvidenceEvaluator.CategoryInput;
import com.steven.assets.service.srpp.RiskEvidenceEvaluator.CategoryResult;
import com.steven.assets.service.srpp.RiskEvidenceEvaluator.EvidenceStatus;
import com.steven.assets.service.srpp.RiskEvidenceEvaluator.RiskMode;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 181／Task 481.6／481.7：{@link RiskEvidenceEvaluator} 與 {@link SrppRiskRubricV1} 的純 JUnit 測試。
 *
 * <p>黃金案例 {@code spec/fixtures/srpp_risk_evidence_golden.json}（先斷言位元組 SHA-256 再使用）逐案還原輸入：
 * {@code assessed=true} 視為 1 筆已驗證來源且 {@code claimedScore=score}，{@code false} 視為 0 筆且
 * {@code claimedScore=0}。另以獨立參考式窮舉六類分數全部組合 × 已評數 0–6（12,096 組）比對 {@code classify}。
 */
class RiskEvidenceEvaluatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOLDEN_SHA256 = "8b534496df63ccc09ff9906598518a6c3f368c5d0fb46a0f779b62dcf3101644";
    private static final String RUBRIC_SHA256 = "3a780e748aa3dc8cc94053178c378069fc54b3c10a6d1a09bd32c9bc0628f378";

    @Test
    void rubricConstantsAndShaMatchSrppPolicy() {
        assertThat(SrppRiskRubricV1.CATEGORY_CODES).containsExactly("fed", "geopolitics", "oil", "taiwan_politics",
                "us_taiwan_inflation", "semiconductor_cycle_and_advanced_process");
        assertThat(SrppRiskRubricV1.CATEGORY_CODES.stream().map(SrppRiskRubricV1::max).toList())
                .containsExactly(2, 3, 2, 3, 2, 3);
        assertThat(SrppRiskRubricV1.DEFENSIVE_CATEGORY_SCORE).isEqualTo(3);
        assertThat(SrppRiskRubricV1.DEFENSIVE_TOTAL_SCORE).isEqualTo(8);
        assertThat(SrppRiskRubricV1.MINIMUM_EVALUATED_CATEGORIES).isEqualTo(4);
        assertThat(SrppRiskRubricV1.CAUTIOUS_TOTAL_SCORE).isEqualTo(5);
        assertThat(SrppJcs.canonicalize(SrppRiskRubricV1.document())).isEqualTo("{\"categories\":["
                + "{\"code\":\"fed\",\"max\":2},{\"code\":\"geopolitics\",\"max\":3},{\"code\":\"oil\",\"max\":2},"
                + "{\"code\":\"taiwan_politics\",\"max\":3},{\"code\":\"us_taiwan_inflation\",\"max\":2},"
                + "{\"code\":\"semiconductor_cycle_and_advanced_process\",\"max\":3}],\"decision\":{"
                + "\"cautiousTotalScore\":5,\"defensiveCategoryScore\":3,\"defensiveTotalScore\":8,"
                + "\"minimumEvaluatedCategories\":4},\"schema\":\"SRPP_RISK_RUBRIC_V1\"}");
        assertThat(SrppRiskRubricV1.rubricSha256()).isEqualTo(RUBRIC_SHA256);
        assertThatThrownBy(() -> SrppRiskRubricV1.max("unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 黃金案例

    private static JsonNode golden() throws Exception {
        Path path = Path.of("spec/fixtures/srpp_risk_evidence_golden.json");
        if (!Files.exists(path)) path = Path.of("../spec/fixtures/srpp_risk_evidence_golden.json");
        byte[] bytes = Files.readAllBytes(path);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .as("黃金案例檔位元組 SHA-256").isEqualTo(GOLDEN_SHA256);
        JsonNode root = JSON.readTree(bytes);
        assertThat(root.path("schema").asText()).isEqualTo("SRPP_RISK_EVIDENCE_GOLDEN_V1");
        return root;
    }

    static Stream<Arguments> goldenCases() throws Exception {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode item : golden().path("cases")) out.add(Arguments.of(item.path("caseId").asText(), item));
        return out.stream();
    }

    @Test
    void goldenFixtureHasTwentyThreeCases() throws Exception {
        assertThat(golden().path("cases").size()).isEqualTo(23);
    }

    @ParameterizedTest(name = "黃金案例 {0}")
    @MethodSource("goldenCases")
    void goldenCaseReproducesStatedTotals(String caseId, JsonNode item) {
        List<CategoryInput> inputs = new ArrayList<>();
        JsonNode categories = item.path("categories");
        assertThat(categories.size()).isEqualTo(6);
        for (String code : SrppRiskRubricV1.CATEGORY_CODES) {
            JsonNode category = categories.path(code);
            boolean assessed = category.path("assessed").booleanValue();
            inputs.add(new CategoryInput(code, assessed ? category.path("score").intValue() : 0, false, assessed ? 1 : 0));
        }

        Assessment assessment = RiskEvidenceEvaluator.evaluate(inputs);

        JsonNode stated = item.path("stated");
        assertThat(assessment.totalScore()).isEqualTo(stated.path("totalScore").intValue());
        assertThat(assessment.assessedCount()).isEqualTo(stated.path("assessedCount").intValue());
        assertThat(assessment.riskMode().name()).isEqualTo(stated.path("riskMode").asText());
        for (CategoryResult result : assessment.categories()) {
            JsonNode expected = categories.path(result.code());
            assertThat(result.evidenceStatus().name()).as(result.code()).isEqualTo(expected.path("evidenceStatus").asText());
            assertThat(result.score()).as(result.code()).isEqualTo(expected.path("score").intValue());
            assertThat(result.assessed()).as(result.code()).isEqualTo(expected.path("assessed").booleanValue());
        }
    }

    // ---------------------------------------------------------------- 窮舉

    /** 依 481.6 文字規則獨立撰寫的參考式（不呼叫被測程式）。 */
    private static String referenceRiskMode(int[] scores, int assessedCount) {
        int total = 0;
        boolean anyAtLeastThree = false;
        for (int score : scores) {
            total += score;
            if (score >= 3) anyAtLeastThree = true;
        }
        if (anyAtLeastThree || total >= 8 || assessedCount < 4) return "DEFENSIVE";
        if (total >= 5) return "CAUTIOUS";
        return "NORMAL";
    }

    @Test
    void classifyMatchesReferenceForAllTwelveThousandNinetySixCombinations() {
        int[] maxima = {2, 3, 2, 3, 2, 3};
        int combinations = 0;
        int[] scores = new int[6];
        for (scores[0] = 0; scores[0] <= maxima[0]; scores[0]++)
            for (scores[1] = 0; scores[1] <= maxima[1]; scores[1]++)
                for (scores[2] = 0; scores[2] <= maxima[2]; scores[2]++)
                    for (scores[3] = 0; scores[3] <= maxima[3]; scores[3]++)
                        for (scores[4] = 0; scores[4] <= maxima[4]; scores[4]++)
                            for (scores[5] = 0; scores[5] <= maxima[5]; scores[5]++)
                                for (int assessed = 0; assessed <= 6; assessed++) {
                                    combinations++;
                                    assertThat(RiskEvidenceEvaluator.classify(scores.clone(), assessed).name())
                                            .as("scores=%s assessed=%d", java.util.Arrays.toString(scores), assessed)
                                            .isEqualTo(referenceRiskMode(scores, assessed));
                                }
        assertThat(combinations).isEqualTo(12_096);
    }

    @Test
    void classifyRejectsMalformedArguments() {
        assertThatThrownBy(() -> RiskEvidenceEvaluator.classify(new int[5], 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskEvidenceEvaluator.classify(null, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskEvidenceEvaluator.classify(new int[6], 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskEvidenceEvaluator.classify(new int[6], -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskEvidenceEvaluator.classify(new int[] {-1, 0, 0, 0, 0, 0}, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 單類規則

    private static List<CategoryInput> allVerifiedZero() {
        List<CategoryInput> inputs = new ArrayList<>();
        for (String code : SrppRiskRubricV1.CATEGORY_CODES) inputs.add(new CategoryInput(code, 0, false, 1));
        return inputs;
    }

    private static List<CategoryInput> with(CategoryInput replacement) {
        List<CategoryInput> inputs = allVerifiedZero();
        inputs.replaceAll(input -> input.code().equals(replacement.code()) ? replacement : input);
        return inputs;
    }

    @Test
    void evidenceStatusFollowsVerifiedSourcesAndClaimedScore() {
        Assessment none = RiskEvidenceEvaluator.evaluate(with(new CategoryInput("fed", 0, false, 0)));
        assertThat(none.categories().get(0)).isEqualTo(new CategoryResult("fed", 0, false, EvidenceStatus.EVIDENCE_INSUFFICIENT));
        assertThat(none.assessedCount()).isEqualTo(5);

        Assessment conflicting = RiskEvidenceEvaluator.evaluate(with(new CategoryInput("oil", 0, true, 3)));
        assertThat(conflicting.categories().get(2)).isEqualTo(new CategoryResult("oil", 0, false, EvidenceStatus.EVIDENCE_INSUFFICIENT));

        Assessment noEvent = RiskEvidenceEvaluator.evaluate(allVerifiedZero());
        assertThat(noEvent.categories()).allMatch(result -> result.assessed()
                && result.evidenceStatus() == EvidenceStatus.VERIFIED_NO_RUBRIC_EVENT && result.score() == 0);
        assertThat(noEvent.riskMode()).isEqualTo(RiskMode.NORMAL);

        Assessment found = RiskEvidenceEvaluator.evaluate(with(new CategoryInput("geopolitics", 3, false, 2)));
        assertThat(found.categories().get(1)).isEqualTo(new CategoryResult("geopolitics", 3, true, EvidenceStatus.RUBRIC_EVENT_FOUND));
        assertThat(found.totalScore()).isEqualTo(3);
        assertThat(found.riskMode()).isEqualTo(RiskMode.DEFENSIVE);
    }

    @Test
    void outputIsAlwaysInRubricOrderRegardlessOfInputOrder() {
        List<CategoryInput> reversed = new ArrayList<>(allVerifiedZero());
        java.util.Collections.reverse(reversed);
        assertThat(RiskEvidenceEvaluator.evaluate(reversed).categories().stream().map(CategoryResult::code).toList())
                .isEqualTo(SrppRiskRubricV1.CATEGORY_CODES);
    }

    @Test
    void scoreWithoutEvidenceRule() {
        assertThat(RiskEvidenceEvaluator.scoreWithoutEvidence(1, false, 0)).isTrue();
        assertThat(RiskEvidenceEvaluator.scoreWithoutEvidence(2, true, 4)).isTrue();
        assertThat(RiskEvidenceEvaluator.scoreWithoutEvidence(0, true, 0)).isFalse();
        assertThat(RiskEvidenceEvaluator.scoreWithoutEvidence(0, false, 0)).isFalse();
        assertThat(RiskEvidenceEvaluator.scoreWithoutEvidence(2, false, 1)).isFalse();
    }

    @Test
    void evaluateRejectsInputsThatMustHaveBeen422() {
        assertThatThrownBy(() -> RiskEvidenceEvaluator.evaluate(with(new CategoryInput("fed", 1, false, 0))))
                .hasMessage("SCORE_WITHOUT_EVIDENCE");
        assertThatThrownBy(() -> RiskEvidenceEvaluator.evaluate(with(new CategoryInput("fed", 3, false, 1))))
                .hasMessage("SCORE_OUT_OF_RANGE");
        assertThatThrownBy(() -> RiskEvidenceEvaluator.evaluate(with(new CategoryInput("fed", -1, false, 1))))
                .hasMessage("SCORE_OUT_OF_RANGE");
        List<CategoryInput> duplicate = allVerifiedZero();
        duplicate.set(1, new CategoryInput("fed", 0, false, 1));
        assertThatThrownBy(() -> RiskEvidenceEvaluator.evaluate(duplicate)).hasMessage("DUPLICATE_CATEGORY");
        List<CategoryInput> unknown = allVerifiedZero();
        unknown.set(0, new CategoryInput("rates", 0, false, 1));
        assertThatThrownBy(() -> RiskEvidenceEvaluator.evaluate(unknown)).hasMessage("UNKNOWN_CATEGORY");
        assertThatThrownBy(() -> RiskEvidenceEvaluator.evaluate(allVerifiedZero().subList(0, 5)))
                .hasMessage("RISK_CATEGORY_COUNT");
    }
}
