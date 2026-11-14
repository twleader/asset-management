package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionPolicyAuthority;
import com.steven.assets.service.srpp.us.UsCoreSellCalculator;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class SrppSettingsFormalGatesTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void nonDefaultKdThresholdsChangeRealStrategicBuyVeto()throws Exception {
        JsonNode all=json.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));JsonNode c=all.path("cases").get(5);ObjectNode body=c.path("body").deepCopy();
        for(JsonNode point:body.path("indicators"))((ObjectNode)point).put("k",85).put("d",75).put("rsi5",50).put("bias10",0).put("wr9",50);
        ObjectNode policy=(ObjectNode)json.readTree(new DecisionPolicyAuthority().modelBytes()).path("execution_policy").path("strategic_buy_high_gate_d188");
        policy.put("kd_overbought_k_greater_than",90).put("kd_overbought_d_greater_than",80);
        assertThat(SrppReportModuleCalculator.strategicGate(body,LocalDate.parse(c.path("date").asText()),policy).path("eligible").asBoolean()).isTrue();
        policy.put("kd_overbought_k_greater_than",80).put("kd_overbought_d_greater_than",70);
        JsonNode blocked=SrppReportModuleCalculator.strategicGate(body,LocalDate.parse(c.path("date").asText()),policy);
        assertThat(blocked.path("eligible").asBoolean()).isFalse();assertThat(blocked.path("reason").asText()).isEqualTo("HIGH_ZONE_OVERHEAT_SIGNAL");
        policy.put("kd_overbought_k_greater_than",95).put("kd_overbought_d_greater_than",90);
        assertThat(SrppReportModuleCalculator.strategicGate(body,LocalDate.parse(c.path("date").asText()),policy).path("eligible").asBoolean()).isTrue();
    }
    @Test void nonDefaultKdThresholdsChangeRealUsCoreArmingWithoutAllowingImmediateSale()throws Exception {
        JsonNode all=json.readTree(getClass().getResourceAsStream("/srpp/us-calculator-parity.json")),c=all.path("core").get(3);ObjectNode model=all.path("model").deepCopy();ObjectNode p=(ObjectNode)model.path("execution_policy").path("core_sell_guard_d174");
        p.put("kd_overbought_k_greater_than",90).put("kd_overbought_d_greater_than",80);JsonNode first=UsCoreSellCalculator.calculate(c.path("input"),model,c.path("ledger"),"Codex").path("results").get(0);assertThat(first.path("overheat_watch").asBoolean()).isTrue();assertThat(first.path("timing_pass").asBoolean()).isFalse();
        p.put("kd_overbought_k_greater_than",96).put("kd_overbought_d_greater_than",86);JsonNode changed=UsCoreSellCalculator.calculate(c.path("input"),model,c.path("ledger"),"Codex").path("results").get(0);assertThat(changed.path("overheat_watch").asBoolean()).isFalse();assertThat(changed.path("status").asText()).isEqualTo("NOT_ARMED");assertThat(changed.path("timing_pass").asBoolean()).isFalse();
        // Independent D-209/D-211 high-zone protections stay at 80, regardless of overheat aliases.
        assertThat(p.path("high_zone_reversal_d209").path("minimum_d_inclusive").asInt()).isEqualTo(80);
    }
}
