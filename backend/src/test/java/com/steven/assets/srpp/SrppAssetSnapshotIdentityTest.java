package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.MAPPER;
import static org.assertj.core.api.Assertions.*;

class SrppAssetSnapshotIdentityTest {
    JsonNode sample()throws Exception{return MAPPER.readTree("{\"id\":15,\"notes\":null,\"totalAssets\":\"123.45\",\"deposits\":[{\"id\":1,\"amount\":\"100\"}],\"funds\":[{\"id\":2,\"units\":\"2.5\"}],\"stocks\":[{\"id\":3,\"stockCode\":\"0050\",\"shares\":\"1\"},{\"id\":4,\"stockCode\":\"00881\",\"shares\":\"2\"}]}");}
    @Test void exactDecimalWireNormalizationIsSharedAndEveryStaticFieldIsRetained()throws Exception{
        JsonNode original=sample();ObjectNode numeric=original.deepCopy();numeric.put("totalAssets",new BigDecimal("123.4500"));((ObjectNode)numeric.path("funds").get(0)).put("units",new BigDecimal("2.5000"));
        assertThat(SrppAssetSnapshotIdentity.matches(original,numeric)).isTrue();
        assertThat(SrppAssetSnapshotIdentity.revision(15,original)).isEqualTo(SrppAssetSnapshotIdentity.revision(15,numeric));
        for(String field:List.of("id","notes","totalAssets","deposits","funds","stocks")){
            ObjectNode missing=original.deepCopy();missing.remove(field);assertThat(SrppAssetSnapshotIdentity.matches(original,missing)).as(field).isFalse();
        }
        for(String group:List.of("deposits","funds","stocks")){
            ObjectNode changed=original.deepCopy();((ObjectNode)changed.path(group).get(0)).put("id",99);assertThat(SrppAssetSnapshotIdentity.matches(original,changed)).isFalse();
        }
        ObjectNode reordered=original.deepCopy();ArrayNode stocks=(ArrayNode)reordered.path("stocks");JsonNode first=stocks.remove(0);stocks.add(first);assertThat(SrppAssetSnapshotIdentity.matches(original,reordered)).isFalse();
        ObjectNode text=original.deepCopy();text.put("notes","0");ObjectNode number=original.deepCopy();number.put("notes",0);assertThat(SrppAssetSnapshotIdentity.matches(text,number)).isFalse();
        assertThat(SrppAssetSnapshotIdentity.matches(null,original)).isFalse();assertThatThrownBy(()->SrppAssetSnapshotIdentity.revision(16,original)).isInstanceOf(IllegalArgumentException.class);
    }
}
