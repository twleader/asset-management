package com.steven.assets.service.srpp.us;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UsReportRequestTest {
    static LinkedMultiValueMap<String,String> query(){var q=new LinkedMultiValueMap<String,String>();q.add("consumer","Codex");q.add("market","US");q.add("sessionDate","2026-10-26");q.add("reportSlot",UsReportRequest.SLOT);q.add("policyBundleSha256",UsReportFixtures.AUTHORITY.decision().policy().bundleHash());q.add("swaggerSha256",UsReportFixtures.SWAGGER);return q;}
    @Test void strictIdentityAndOnlyPublishedSelectors(){assertThat(UsReportRequest.parse(query()).sessionDate()).isEqualTo(UsReportFixtures.DAY);for(String key:List.of("ownerId","price","target_price","tradingDate","slot","asOf","force","refresh")){var q=query();q.add(key,"1");assertThatThrownBy(()->UsReportRequest.parse(q)).isInstanceOf(UsReportProblem.class);}for(String key:query().keySet()){var duplicate=query();duplicate.add(key,duplicate.getFirst(key));assertThatThrownBy(()->UsReportRequest.parse(duplicate)).isInstanceOf(UsReportProblem.class);var blank=query();blank.set(key," ");assertThatThrownBy(()->UsReportRequest.parse(blank)).isInstanceOf(UsReportProblem.class);}}
    @Test void rejectsTaiwanMarketSlotsMalformedDateHashesAndUuid(){for(var entry:Map.of("market","TW","reportSlot","12:00","sessionDate","2026-02-30","consumer","codex","policyBundleSha256","A".repeat(64),"swaggerSha256","x").entrySet()){var q=query();q.set(entry.getKey(),entry.getValue());assertThatThrownBy(()->UsReportRequest.parse(q)).isInstanceOf(UsReportProblem.class);}var q=query();q.add("calculationRunId","wrong");assertThatThrownBy(()->UsReportRequest.parse(q)).isInstanceOf(UsReportProblem.class);}
}
