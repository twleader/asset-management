package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 163／Task 452.3：RFC 8785 JCS 與 proposal 合成範例 golden hash。 */
class SrppJcsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture(String name) throws Exception {
        try (InputStream in = SrppJcsTest.class.getResourceAsStream("/srpp/" + name)) {
            assertThat(in).as(name).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"summary-complete.json", "summary-partial.json", "summary-stale-pinned.json"})
    void proposalSummaryContextHashesMatch(String name) throws Exception {
        JsonNode summary = fixture(name);
        assertThat(SrppJcs.hash(summary.get("context")))
                .isEqualTo(summary.get("contextContentSha256").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"evidence-assets.json", "evidence-calendar.json", "evidence-policy.json",
            "evidence-complete-assets.json", "evidence-complete-calendar.json", "evidence-complete-policy.json",
            "evidence-complete-technicals.json"})
    void proposalEvidenceBodyHashesMatch(String name) throws Exception {
        JsonNode evidence = fixture(name);
        assertThat(SrppJcs.sha256Hex(evidence.get("body").textValue()))
                .isEqualTo(evidence.get("bodySha256").asText());
    }

    @Test
    void sortsKeysByUtf16CodeUnitsAndHasNoWhitespace() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("b", 1);
        node.put("a", true);
        node.put("😀", "emoji");   // surrogate pair (0xD83D) sorts before U+FB01 in UTF-16
        node.put("ﬁ", "ligature");
        node.putNull("A");
        node.putArray("c").add("x").add(2);
        assertThat(SrppJcs.canonicalize(node))
                .isEqualTo("{\"A\":null,\"a\":true,\"b\":1,\"c\":[\"x\",2],\"😀\":\"emoji\",\"ﬁ\":\"ligature\"}");
    }

    @Test
    void escapesOnlyRequiredCharactersWithLowercaseHex() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("s", "q\" b\\ \b\f\n\r\t \u0001\u001f / 台股 \u007f");
        assertThat(SrppJcs.canonicalize(node))
                .isEqualTo("{\"s\":\"q\\\" b\\\\ \\b\\f\\n\\r\\t \\u0001\\u001f / 台股 \u007f\"}");
    }

    @Test
    void keepsNonAsciiAsUtf8InHash() {
        assertThat(SrppJcs.sha256Hex("台股"))
                .isEqualTo("63d6b93577aa97d124c8c0e7e1a7c818f7aabedc0c3eeb70aaf272f34bd1e07b");
        assertThat(SrppJcs.sha256Hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(SrppJcs.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void rejectsFloatsNonIntegersAndUnsafeIntegers() {
        assertThatThrownBy(() -> SrppJcs.canonicalize(DoubleNode.valueOf(1.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SrppJcs.canonicalize(DoubleNode.valueOf(1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SrppJcs.canonicalize(DecimalNode.valueOf(new BigDecimal("0.05"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SrppJcs.canonicalize(LongNode.valueOf(9_007_199_254_740_992L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SrppJcs.canonicalize(SrppJcs.parseStrict("{\"a\":1.0}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SrppJcs.canonicalize(LongNode.valueOf(-9_007_199_254_740_991L))).isEqualTo("-9007199254740991");
        assertThat(SrppJcs.canonicalize(DecimalNode.valueOf(new BigDecimal("1E+3")))).isEqualTo("1000");
    }

    @Test
    void strictParseRejectsDuplicateKeysAndTrailingTokens() {
        assertThatThrownBy(() -> SrppJcs.parseStrict("{\"a\":\"1\",\"a\":\"2\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SrppJcs.parseStrict("{} {}")).isInstanceOf(IllegalArgumentException.class);
    }
}
