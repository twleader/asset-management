package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class SrppSettingsCanonicalTest {
    @Test void pythonRfc8785GoldenMatchesDecimalExponentNegativeZeroAndUtf16()throws Exception {
        String raw=new String(getClass().getResourceAsStream("/srpp/settings-canonicalization.json").readAllBytes(),StandardCharsets.UTF_8);
        for(JsonNode c:SettingsCanonicalJson.parse(raw).path("cases")) {
            assertThat(SettingsCanonicalJson.canonical(c.path("value"))).isEqualTo(c.path("canonical").asText());
            assertThat(SettingsCanonicalJson.hash(c.path("value"))).isEqualTo(c.path("sha256").asText());
        }
        assertThat(SettingsCanonicalJson.canonical(SettingsCanonicalJson.parse("{\"x\":1e20}"))).isEqualTo("{\"x\":100000000000000000000}");
    }
    @Test void malformedOrAmbiguousJsonIsRejected() {
        for(String raw:new String[]{"{\"x\":1,\"x\":2}","{} {}","{\"x\":NaN}","{\"x\":Infinity}","{\"x\":1e999}","{\"x\":\"\\ud800\"}","{\"x\":9007199254740992}"})
            assertThatThrownBy(()->SettingsCanonicalJson.parse(raw)).as(raw).isInstanceOf(IllegalArgumentException.class);
    }
}
