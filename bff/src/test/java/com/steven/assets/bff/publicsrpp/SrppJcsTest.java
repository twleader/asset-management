package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SrppJcsTest {

    @Test
    void proposalSummaryContextsHashToTheirDeclaredDigest() {
        for (String name : SrppTestFixtures.SUMMARIES) {
            ObjectNode summary = SrppTestFixtures.tree(name);
            String actual = SrppJcs.sha256Hex(SrppJcs.canonicalize(summary.get("context")));
            assertThat(actual).as(name).isEqualTo(summary.get("contextContentSha256").textValue());
        }
    }

    @Test
    void proposalEvidenceBodiesHashToTheirDeclaredDigest() {
        for (String name : SrppTestFixtures.EVIDENCES) {
            JsonNode evidence = SrppTestFixtures.tree(name);
            assertThat(SrppJcs.sha256Hex(evidence.get("body").textValue())).as(name)
                    .isEqualTo(evidence.get("bodySha256").textValue());
        }
    }

    @Test
    void sortsKeysByUtf16AndEscapesPerRfc8785() throws Exception {
        JsonNode node = SrppTestFixtures.JSON.readTree(
                "{\"b\":[1,true,null],\"a\":\"q\\\"\\\\\\n\\t\\u0001\\u001f/中\",\"B\":{\"z\":1,\"y\":\"\"}}");
        assertThat(SrppJcs.canonicalize(node))
                .isEqualTo("{\"B\":{\"y\":\"\",\"z\":1},\"a\":\"q\\\"\\\\\\n\\t\\u0001\\u001f/中\",\"b\":[1,true,null]}");
    }

    @Test
    void rejectsFloatsAndUnsafeIntegers() {
        var factory = SrppTestFixtures.JSON.getNodeFactory();
        assertThatThrownBy(() -> SrppJcs.canonicalize(factory.numberNode(new BigDecimal("1.5"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SrppJcs.canonicalize(factory.numberNode(1.0d)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SrppJcs.canonicalize(factory.numberNode(BigInteger.valueOf(9_007_199_254_740_992L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SrppJcs.canonicalize(factory.numberNode(-9_007_199_254_740_991L))).isEqualTo("-9007199254740991");
    }
}
