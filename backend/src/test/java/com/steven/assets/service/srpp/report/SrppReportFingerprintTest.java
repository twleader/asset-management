package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SrppReportFingerprintTest {
    @Test void transportClocksAreExcludedButTrueQuoteAndPersistedMinuteRevisionsAreRetained(){
        var before=frozen(7,AT,"09:05","stable");var after=frozen(7,AT.plusSeconds(60),"09:05","stable");
        assertThat(SrppReportFingerprint.hash(before.input(),before.sourceVector())).isEqualTo(SrppReportFingerprint.hash(after.input(),after.sourceVector()));
        ObjectNode input=before.input(),vector=before.sourceVector();ObjectNode radar=(ObjectNode)input.path("sources").path("radar:00865B");radar.put("generatedAt",AT.toString());update(vector,"radar:00865B",radar,AT.toString());String first=SrppReportFingerprint.hash(input,vector);radar.put("generatedAt",AT.plusSeconds(60).toString());update(vector,"radar:00865B",radar,AT.plusSeconds(60).toString());assertThat(SrppReportFingerprint.hash(input,vector)).isEqualTo(first);
        ObjectNode quote=(ObjectNode)input.path("sources").path("quote:00865B");quote.put("updatedAt",AT.toString()).put("price","9.81");update(vector,"quote:00865B",quote,AT.toString());assertThat(SrppReportFingerprint.hash(input,vector)).isNotEqualTo(first);
        String priceHash=SrppReportFingerprint.hash(input,vector);ObjectNode minute=(ObjectNode)input.path("sources").path("minute:00865B");minute.putObject("capture").put("requestStartedAt",AT.toString()).put("capturedAt",AT.plusSeconds(1).toString());update(vector,"minute:00865B",minute,AT.plusSeconds(1).toString());assertThat(SrppReportFingerprint.hash(input,vector)).isNotEqualTo(priceHash);
    }
    @Test void allExtraRadarReadClocksAreExcludedWithoutDiscardingSourceQuoteTimes(){
        var original=frozen(7,AT,"09:05","stable");ObjectNode input=original.input(),vector=original.sourceVector();ObjectNode body=(ObjectNode)input.path("sources").path("market:台股:00865B");body.putObject("radar").put("generatedAt",AT.toString());((ObjectNode)input.path("sources").path("symbols")).set("台股:00865B",body.deepCopy());update(vector,"market:台股:00865B",body,SrppJcs.hash(body));update(vector,"symbols",input.path("sources").path("symbols"),SrppJcs.hash(input.path("sources").path("symbols")));String first=SrppReportFingerprint.hash(input,vector);
        body.withObject("radar").put("generatedAt",AT.plusSeconds(60).toString());((ObjectNode)input.path("sources").path("symbols")).set("台股:00865B",body.deepCopy());update(vector,"market:台股:00865B",body,SrppJcs.hash(body));update(vector,"symbols",input.path("sources").path("symbols"),SrppJcs.hash(input.path("sources").path("symbols")));assertThat(SrppReportFingerprint.hash(input,vector)).isEqualTo(first);
    }
    static void update(ObjectNode vector,String id,JsonNode body,String revision){((ObjectNode)vector.path(id)).put("sha256",SrppJcs.hash(body)).put("revision",revision);}
}
