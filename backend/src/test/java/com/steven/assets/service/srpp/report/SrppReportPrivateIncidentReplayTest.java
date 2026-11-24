package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.AssetService;
import com.steven.assets.srpp.SrppAssetSnapshotIdentity;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Opt-in local replay: no private payload, account identity, holding, or amount is embedded in Git or output. */
@EnabledIfEnvironmentVariable(named="SRPP_INCIDENT_DIR",matches=".+")
class SrppReportPrivateIncidentReplayTest {
    @Test void ignoredOriginalIncidentStillFailsCompleteStaticBindingWithoutGrantingOwnerOrTradeAuthority() throws Exception {
        Path directory=Path.of(System.getenv("SRPP_INCIDENT_DIR"));
        byte[] assetBytes=Files.readAllBytes(directory.resolve("assets.json"));
        byte[] calculationBytes=Files.readAllBytes(directory.resolve("calculations.json"));
        JsonNode direct=MAPPER.readTree(assetBytes).path("snapshot"),envelope=MAPPER.readTree(calculationBytes);
        JsonNode frozen=SrppJcs.parseStrict(envelope.path("inputSnapshot").path("inputBundleJcs").asText()).path("sources").path("assets").path("snapshot");
        assertThat(direct.path("id").equals(frozen.path("id"))).as("same mutable snapshot identity").isTrue();
        assertThat(SrppAssetSnapshotIdentity.matches(frozen,direct)).as("complete static mismatch must remain rejected").isFalse();
        assertThat(direct.path("stocks").size()==frozen.path("stocks").size()).as("same row count").isTrue();
        int changedIdentities=0,changedValues=0;
        for(int i=0;i<direct.path("stocks").size();i++){
            JsonNode current=direct.path("stocks").get(i),old=frozen.path("stocks").get(i);
            for(String identity:List.of("stockCode","market","brokerId"))assertThat(current.path(identity).equals(old.path(identity))).as("same source holding tuple").isTrue();
            for(String quantity:List.of("shares","investmentCost"))assertThat(new BigDecimal(current.path(quantity).asText()).compareTo(new BigDecimal(old.path(quantity).asText()))==0).as("unchanged quantity and cost").isTrue();
            if(!current.path("id").equals(old.path("id")))changedIdentities++;
            if(new BigDecimal(current.path("currentValue").asText()).compareTo(new BigDecimal(old.path("currentValue").asText()))!=0)changedValues++;
        }
        assertThat(changedIdentities).isEqualTo(19);assertThat(changedValues).isEqualTo(9);
        long owner=envelope.path("identity").path("ownerUserId").asLong();
        var repository=mock(AssetSnapshotRepository.class);var assets=mock(AssetService.class);
        var entity=AssetSnapshot.builder().id(direct.path("id").asLong()).ownerUserId(owner).build();
        when(repository.findLatestWithStocksByOwnerUserId(owner)).thenReturn(Optional.of(entity));
        when(assets.getSnapshotDetail(entity)).thenReturn(MAPPER.treeToValue(direct,AssetSnapshotDto.SnapshotDetailResponse.class));
        assertThat(new SrppReportAssetRevision(repository,assets,MAPPER).matches(owner,frozen)).as("latest fence must reject original incident").isFalse();
        var receipt=MAPPER.createObjectNode().put("status","PASS").put("sameSnapshotId",true)
                .put("changedRowIdentities",changedIdentities).put("changedMarketValues",changedValues)
                .put("staticMismatchRejected",true).put("tradeAuthorization",false)
                .put("assetsSha256",sha(assetBytes)).put("calculationsSha256",sha(calculationBytes));
        Path output=Path.of("target","incident-static-replay.json");Files.createDirectories(output.getParent());Files.writeString(output,SrppJcs.canonicalize(receipt)+"\n");
        System.out.println(SrppJcs.canonicalize(receipt));
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
