package com.steven.assets.service.srpp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SrppSettingsRiskV2Test {
    @Test void exactPythonDigestAndHistoricalV1RemainStable() {
        assertThat(new SrppRiskRubricV2(5,8).rubricSha256()).isEqualTo("5ca0f8fd3b82efd5c91e2f40dbe56045c9909ecc4cd966d073d932203bdb1c0b");
        assertThat(SrppRiskRubricV1.rubricSha256()).isEqualTo("3a780e748aa3dc8cc94053178c378069fc54b3c10a6d1a09bd32c9bc0628f378");
    }
    @Test void nonDefaultTotalsChangeActualEvaluatorWhileSingleCategoryAndCoverageVetoRemain() {
        List<RiskEvidenceEvaluator.CategoryInput> inputs=new ArrayList<>();
        int[] scores={1,1,1,1,0,0};int i=0;for(String code:SrppRiskRubricV1.CATEGORY_CODES)inputs.add(new RiskEvidenceEvaluator.CategoryInput(code,scores[i++],false,1));
        assertThat(RiskEvidenceEvaluator.evaluate(inputs).riskMode()).isEqualTo(RiskEvidenceEvaluator.RiskMode.NORMAL);
        assertThat(RiskEvidenceEvaluator.evaluate(inputs,new SrppRiskRubricV2(4,7)).riskMode()).isEqualTo(RiskEvidenceEvaluator.RiskMode.CAUTIOUS);
        assertThat(RiskEvidenceEvaluator.evaluate(inputs,new SrppRiskRubricV2(2,4)).riskMode()).isEqualTo(RiskEvidenceEvaluator.RiskMode.DEFENSIVE);
        assertThat(RiskEvidenceEvaluator.classify(new int[]{0,3,0,0,0,0},6,new SrppRiskRubricV2(10,15))).isEqualTo(RiskEvidenceEvaluator.RiskMode.DEFENSIVE);
        assertThat(RiskEvidenceEvaluator.classify(new int[]{0,0,0,0,0,0},3,new SrppRiskRubricV2(10,15))).isEqualTo(RiskEvidenceEvaluator.RiskMode.DEFENSIVE);
    }
}
