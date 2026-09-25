package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.steven.assets.dto.LatestAssetsDto;
import com.steven.assets.service.LatestAssetsService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Requirement 163／Task 452.6：來源凍結、MIN_LIVE_STOCK_UPDATED_AT_V1 dataAsOf golden。 */
class SrppSourceCaptureTest {
    private static final Instant CAPTURED = Instant.parse("2026-09-24T01:05:30.750Z");   // 09:05:30.750 台北

    private final LatestAssetsService latest = mock(LatestAssetsService.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SrppSourceCapture capture =
            new SrppSourceCapture(latest, mapper, Clock.fixed(CAPTURED, ZoneId.of("Asia/Taipei")));

    private static SrppTestData data() {
        return new SrppTestData()
                .deposit(1, 1L, "b", "活存", "1000", null, "TWD", null)
                .stock(1, "台股", "0050", "1", "100", "1", "100", Instant.parse("2026-09-24T01:04:59Z"))
                .stock(2, "台股", "2330", "1", "200", "1", "200", Instant.parse("2026-09-24T01:03:10.900Z"))
                .stock(3, "美股", "VOO", "1", "300", "1", "300", null);
    }

    @Test
    void dataAsOfIsMinimumLiveUpdatedAtGolden() {
        LatestAssetsDto.Response response = data().build();
        assertThat(SrppSourceCapture.assetsDataAsOf(response.liveAssets(), CAPTURED))
                .isEqualTo(Instant.parse("2026-09-24T01:03:10.900Z"));
        assertThat(SrppTime.format(SrppSourceCapture.assetsDataAsOf(response.liveAssets(), CAPTURED)))
                .isEqualTo("2026-09-24T09:03:10+08:00");
        assertThat(SrppSourceCapture.assetsDataAsOf(new SrppTestData().build().liveAssets(), CAPTURED)).isEqualTo(CAPTURED);
    }

    @Test
    void futureUpdatedAtIsRejected() {
        SrppTestData data = new SrppTestData()
                .stock(1, "台股", "0050", "1", "100", "1", "100", CAPTURED.plusMillis(1));
        assertThatThrownBy(() -> SrppSourceCapture.assetsDataAsOf(data.build().liveAssets(), CAPTURED))
                .isInstanceOf(SrppRejectedException.class).hasMessage("FUTURE_DATA_AS_OF");
    }

    @Test
    void capturesThreeSortedSourcesWithRawBodies() throws Exception {
        LatestAssetsDto.Response response = data().build();
        when(latest.getLatestForOwner(7L)).thenReturn(response);
        SupportedPolicy policy = SrppTestData.policy(Map.of("CASH:TWD:活存", "0.5"));

        SrppCapture captured = capture.capture(7L, policy, SrppTestData.DATE, "09:05");

        assertThat(captured.sources()).extracting(SrppSourceEvidence::sourceId).containsExactly("assets", "calendar", "policy");
        SrppSourceEvidence assets = captured.sources().get(0);
        assertThat(assets.body()).isEqualTo(mapper.writeValueAsString(response));
        assertThat(assets.revision()).isEqualTo(SrppSnapshotRevision.of(response.snapshot()));
        assertThat(captured.assetsRevision()).isEqualTo(assets.revision());
        assertThat(assets.capturedAt()).isEqualTo(CAPTURED);

        SrppSourceEvidence calendar = captured.sources().get(1);
        assertThat(calendar.body()).isEqualTo("{\"authority\":\"MARKET_DATA_SERVICE\",\"date\":\"2026-09-24\","
                + "\"market\":\"台股\",\"schema\":\"SRPP_CALENDAR_EVIDENCE_V1\",\"twTrading\":true}");
        assertThat(calendar.revision()).isEqualTo("calendar-2026-09-24-open");
        assertThat(calendar.dataAsOf()).isEqualTo(CAPTURED);

        SrppSourceEvidence policySource = captured.sources().get(2);
        assertThat(policySource.revision()).isEqualTo("policy-" + policy.calculationPolicySha256());
        assertThat(policySource.dataAsOf()).isEqualTo(policy.registeredAt());
        assertThat(policySource.body()).startsWith("{\"calculationPolicy\":{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\"")
                .contains("\"formulaManifest\":" + SrppFormulaCatalog.manifestJcs())
                .contains("\"policyBundleSha256\":\"" + "a".repeat(64) + "\"");
        assertThat(policySource.bodySha256()).isEqualTo(SrppJcs.sha256Hex(policySource.body()));
    }
}
