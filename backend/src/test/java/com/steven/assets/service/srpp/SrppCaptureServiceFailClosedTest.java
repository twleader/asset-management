package com.steven.assets.service.srpp;

import com.steven.assets.service.srpp.decision.DecisionRequest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** Task488 replaces the diagnostic placeholder with strict frozen-input evaluation. */
class SrppCaptureServiceFailClosedTest {
    private static String body(String extra){return "{\"tradingDate\":\"2026-10-08\",\"slot\":\"09:05\",\"policyBundleSha256\":\""+"a".repeat(64)+"\",\"swaggerSha256\":\""+"b".repeat(64)+"\""+extra+"}";}
    @Test void ownerMayBeOmittedButCallerFactsCannotBeInjected(){assertThat(DecisionRequest.parse(body("")).ownerEmail()).isNull();assertThatThrownBy(()->DecisionRequest.parse(body(",\"candidates\":[]"))).isInstanceOf(SrppCaptureProblem.class);}
    @Test void duplicateAndMalformedBodiesFailBeforeSources(){for(String raw:new String[]{"[]","null","{} {}",body(",\"slot\":\"11:40\""),body(",\"ownerEmail\":null")})assertThatThrownBy(()->DecisionRequest.parse(raw)).isInstanceOf(SrppCaptureProblem.class);}
    @Test void futureSlotAndDifferentDateAreRejected(){DecisionRequest r=DecisionRequest.parse(body(""));assertThatThrownBy(()->r.checkClock(Instant.parse("2026-10-08T01:04:59Z"))).isInstanceOf(SrppCaptureProblem.class);assertThatThrownBy(()->r.checkClock(Instant.parse("2026-10-09T01:05:00Z"))).isInstanceOf(SrppCaptureProblem.class);r.checkClock(Instant.parse("2026-10-08T01:05:00Z"));}
}
