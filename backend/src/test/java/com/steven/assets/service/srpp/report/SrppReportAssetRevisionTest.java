package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.AssetService;
import com.steven.assets.srpp.SrppAssetSnapshotIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.Optional;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.MAPPER;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SrppReportAssetRevisionTest {
    static JsonNode deidentifiedFixture() throws Exception {
        try(var input=new ClassPathResource("srpp/report-noon-20261124-static-snapshots.json").getInputStream()){
            return MAPPER.readTree(input);
        }
    }
    @Test void deidentifiedEquivalentSameIdChangedHoldingsAreRejectedAndExactCurrentSnapshotCanBindOwner() throws Exception {
        JsonNode fixture=deidentifiedFixture(),direct=fixture.path("directSnapshot"),old=fixture.path("packageSnapshot");
        assertThat(direct.path("id")).isEqualTo(old.path("id"));
        var snapshot=AssetSnapshot.builder().id(900001L).ownerUserId(7L).build();
        var repository=mock(AssetSnapshotRepository.class);var assets=mock(AssetService.class);
        when(repository.findLatestWithStocksByOwnerUserId(7L)).thenReturn(Optional.of(snapshot));
        when(assets.getSnapshotDetail(snapshot)).thenReturn(MAPPER.treeToValue(direct,AssetSnapshotDto.SnapshotDetailResponse.class));
        var revision=new SrppReportAssetRevision(repository,assets,MAPPER);
        assertThat(revision.matches(7L,old)).isFalse();
        assertThat(revision.matches(7L,SrppAssetSnapshotIdentity.normalize(direct))).isTrue();
        verify(repository,times(2)).findLatestWithStocksByOwnerUserId(7L);
        verify(repository,never()).findLatestWithStocks();
        verify(assets,never()).getSnapshotDetail(anyLong());
    }
    @Test void missingOrWrongOwnerNeverProjectsOrBorrowOtherOwnersSnapshot() {
        var repository=mock(AssetSnapshotRepository.class);var assets=mock(AssetService.class);
        when(repository.findLatestWithStocksByOwnerUserId(7L)).thenReturn(Optional.empty(),Optional.of(AssetSnapshot.builder().id(900001L).ownerUserId(8L).build()));
        var revision=new SrppReportAssetRevision(repository,assets,MAPPER);
        assertThat(revision.matches(7L,MAPPER.createObjectNode())).isFalse();
        assertThat(revision.matches(7L,MAPPER.createObjectNode())).isFalse();
        verifyNoInteractions(assets);
    }
}
