package com.steven.assets.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.steven.assets.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class HistoryDtoJsonCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    static Stream<Class<?>> entities() {
        return Stream.of(StockPriceHistory.class, ExchangeRateHistory.class, CommodityPriceHistory.class,
                TaiwanGdpPerCapitaHistory.class, JapanGdpPerCapitaHistory.class, KoreaGdpPerCapitaHistory.class,
                TwseIndexDailyHistory.class, UsIndexDailyHistory.class);
    }
    @ParameterizedTest
    @MethodSource("entities")
    void everySerializedFieldIncludingNullAndSourceMetadataSurvivesProjection(Class<?> type) throws Exception {
        Object row = type.getConstructor().newInstance();
        assertSameJson(row);
        int ordinal = 1;
        for (var field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value;
            if (field.getType() == BigDecimal.class) value = new BigDecimal(ordinal + ".1234");
            else if (field.getType() == String.class) value = field.getName() + "-來源";
            else if (field.getType() == LocalDate.class) value = LocalDate.of(2026, 10, 20).plusDays(ordinal);
            else if (field.getType() == Instant.class) value = Instant.parse("2026-10-20T01:02:03Z").plusSeconds(ordinal);
            else if (field.getType() == Long.class) value = 10000000000L + ordinal;
            else if (field.getType() == Integer.class) value = 2020 + ordinal;
            else throw new AssertionError("Add fixture for " + field);
            field.set(row, value);
            ordinal++;
        }
        assertSameJson(row);
    }
    @Test
    void exchangeRateComputedGetterFallbacksRemainPresent() throws Exception {
        ExchangeRateHistory row = ExchangeRateHistory.builder().buyRate(new BigDecimal("31.1")).build();
        assertSameJson(row);
        row.setBuyRate(null);
        row.setSellRate(new BigDecimal("32.2"));
        assertSameJson(row);
        assertThat(mapper.valueToTree(HistoryDto.ExchangeRateHistory.from(row)).has("midRate")).isTrue();
        assertThat(mapper.valueToTree(HistoryDto.ExchangeRateHistory.from(row)).has("fundValuationRate")).isTrue();
    }
    private void assertSameJson(Object row) throws Exception {
        Class<?> dto = Class.forName(HistoryDto.class.getName() + "$" + row.getClass().getSimpleName());
        Object projection = dto.getMethod("from", row.getClass()).invoke(null, row);
        com.fasterxml.jackson.databind.JsonNode projectedJson = mapper.valueToTree(projection);
        com.fasterxml.jackson.databind.JsonNode originalJson = mapper.valueToTree(row);
        assertThat(projectedJson).isEqualTo(originalJson);
    }
}
