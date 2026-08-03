package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 286：驗證 MacroDataFetchClient 的 TWSE 大盤 OHLC ＋ 成交量 join 邏輯。
 * 核心不變量：FMTQIK（成交量來源）失敗或該日查無對應，不得造成 MI_5MINS_HIST（OHLC 來源）
 * 的資料列被吃掉——量欄留 null 即可，OHLC 本身必須完整保留。
 */
class MacroDataFetchClientTurnoverJoinTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 真實 TWSE FMTQIK 回應（2026-08-02 擷取自 https://www.twse.com.tw/rwd/zh/afterTrading/FMTQIK?date=20260701&response=json），僅含 115/07/01、115/07/02 兩日。 */
    private static final String FMTQIK_JSON = """
            {"stat":"OK","date":"20260701","title":"115年07月市場成交資訊","hints":"單位：元、股",
             "fields":["日期","成交股數","成交金額","成交筆數","發行量加權股價指數","漲跌點數"],
             "data":[["115/07/01","14,683,404,939","1,367,817,795,171","6,457,744","47,018.99","893.08"],
                     ["115/07/02","11,740,053,658","1,083,583,417,368","5,564,334","46,744.16","-274.83"]]}
            """;

    /**
     * MI_5MINS_HIST 風格 OHLC 三日：115/07/01、115/07/02 與 FMTQIK 對得上，
     * 115/07/03 FMTQIK 查無對應（用來驗證「缺量不缺價」）。
     */
    private static final String OHLC_JSON = """
            {"stat":"OK","date":"20260701","title":"115年07月每日收盤行情",
             "fields":["日期","開盤指數","最高指數","最低指數","收盤指數"],
             "data":[["115/07/01","46,980.00","47,050.00","46,950.00","47,018.99"],
                     ["115/07/02","47,010.00","47,020.00","46,700.00","46,744.16"],
                     ["115/07/03","46,750.00","46,820.00","46,700.00","46,780.62"]]}
            """;

    @Test
    void join_matchesByDateAndKeepsUnmatchedRowWithNullVolume() throws Exception {
        JsonNode turnoverRoot = MAPPER.readTree(FMTQIK_JSON);
        Map<LocalDate, MacroDataFetchClient.Turnover> turnovers =
                MacroDataFetchClient.parseTwseTurnoverJson(turnoverRoot);

        JsonNode ohlcRoot = MAPPER.readTree(OHLC_JSON);
        List<MacroDataFetchClient.DailyOhlc> rows =
                MacroDataFetchClient.parseTwseMonthlyOhlcJson(ohlcRoot, turnovers);

        assertThat(rows).hasSize(3);

        MacroDataFetchClient.DailyOhlc day1 = rows.get(0);
        assertThat(day1.tradingDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(day1.close()).isEqualByComparingTo(new BigDecimal("47018.99"));
        assertThat(day1.volume()).isEqualTo(14683404939L);
        assertThat(day1.value()).isEqualByComparingTo(new BigDecimal("1367817795171"));

        MacroDataFetchClient.DailyOhlc day2 = rows.get(1);
        assertThat(day2.tradingDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(day2.close()).isEqualByComparingTo(new BigDecimal("46744.16"));
        assertThat(day2.volume()).isEqualTo(11740053658L);
        assertThat(day2.value()).isEqualByComparingTo(new BigDecimal("1083583417368"));

        // 第三日 FMTQIK 查無對應：量欄 null，但 OHLC 仍完整保留（不得因缺量而整列消失）
        MacroDataFetchClient.DailyOhlc day3 = rows.get(2);
        assertThat(day3.tradingDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(day3.open()).isEqualByComparingTo(new BigDecimal("46750.00"));
        assertThat(day3.high()).isEqualByComparingTo(new BigDecimal("46820.00"));
        assertThat(day3.low()).isEqualByComparingTo(new BigDecimal("46700.00"));
        assertThat(day3.close()).isEqualByComparingTo(new BigDecimal("46780.62"));
        assertThat(day3.volume()).isNull();
        assertThat(day3.value()).isNull();
    }

    /**
     * 核心不變量：FMTQIK 整月抓取失敗（fetchTwseTurnoverMonthly 任何例外／非 2xx／stat 非 OK
     * 皆回空 map）時，呼叫端會把空 map 傳進 join——OHLC 三列必須原封不動地全部保留，
     * 只是量欄整批變 null。這是本功能最重要的回歸測試：量的抓取失敗絕不能連帶讓價的資料悄悄消失。
     */
    @Test
    void fmtqikTotalFailure_stillReturnsAllOhlcRowsWithNullVolume() throws Exception {
        JsonNode ohlcRoot = MAPPER.readTree(OHLC_JSON);
        List<MacroDataFetchClient.DailyOhlc> rows =
                MacroDataFetchClient.parseTwseMonthlyOhlcJson(ohlcRoot, Collections.emptyMap());

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(MacroDataFetchClient.DailyOhlc::tradingDate)
                .containsExactly(
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 7, 3));
        assertThat(rows).extracting(MacroDataFetchClient.DailyOhlc::close)
                .containsExactly(
                        new BigDecimal("47018.99"),
                        new BigDecimal("46744.16"),
                        new BigDecimal("46780.62"));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.volume()).isNull();
            assertThat(row.value()).isNull();
        });
        // 開高低仍完整，未因量欄消失而被連帶清空
        assertThat(rows.get(0).open()).isEqualByComparingTo(new BigDecimal("46980.00"));
        assertThat(rows.get(0).high()).isEqualByComparingTo(new BigDecimal("47050.00"));
        assertThat(rows.get(0).low()).isEqualByComparingTo(new BigDecimal("46950.00"));
    }

    @Test
    void turnoverJson_statNotOk_returnsEmptyMap() throws Exception {
        JsonNode root = MAPPER.readTree("""
                {"stat":"ERROR","data":[["115/07/01","14,683,404,939","1,367,817,795,171"]]}
                """);
        assertThat(MacroDataFetchClient.parseTwseTurnoverJson(root)).isEmpty();
    }

    @Test
    void turnoverJson_dataNotArray_returnsEmptyMap() throws Exception {
        JsonNode root = MAPPER.readTree("""
                {"stat":"OK","data":"沒有資料"}
                """);
        assertThat(MacroDataFetchClient.parseTwseTurnoverJson(root)).isEmpty();
    }

    @Test
    void turnoverJson_dataEmptyArray_returnsEmptyMap() throws Exception {
        JsonNode root = MAPPER.readTree("""
                {"stat":"OK","data":[]}
                """);
        assertThat(MacroDataFetchClient.parseTwseTurnoverJson(root)).isEmpty();
    }

    @Test
    void turnoverJson_dashInVolumeColumn_yieldsNullVolumeButParsesValue() throws Exception {
        JsonNode root = MAPPER.readTree("""
                {"stat":"OK","data":[["115/07/05","-","1,000,000,000","100","47,000.00","0.00"]]}
                """);
        Map<LocalDate, MacroDataFetchClient.Turnover> turnovers =
                MacroDataFetchClient.parseTwseTurnoverJson(root);
        MacroDataFetchClient.Turnover t = turnovers.get(LocalDate.of(2026, 7, 5));
        assertThat(t).isNotNull();
        assertThat(t.volume()).isNull();
        assertThat(t.value()).isEqualByComparingTo(new BigDecimal("1000000000"));
    }

    @Test
    void turnoverJson_dashInValueColumn_yieldsNullValueButParsesVolume() throws Exception {
        JsonNode root = MAPPER.readTree("""
                {"stat":"OK","data":[["115/07/06","5,000,000","-","100","47,000.00","0.00"]]}
                """);
        Map<LocalDate, MacroDataFetchClient.Turnover> turnovers =
                MacroDataFetchClient.parseTwseTurnoverJson(root);
        MacroDataFetchClient.Turnover t = turnovers.get(LocalDate.of(2026, 7, 6));
        assertThat(t).isNotNull();
        assertThat(t.volume()).isEqualTo(5000000L);
        assertThat(t.value()).isNull();
    }

    @Test
    void turnoverJson_malformedRocDate_rowSilentlySkipped() throws Exception {
        JsonNode root = MAPPER.readTree("""
                {"stat":"OK","data":[
                    ["115/07","11,740,053,658","1,083,583,417,368","5,564,334","46,744.16","-274.83"],
                    ["115/07/02","11,740,053,658","1,083,583,417,368","5,564,334","46,744.16","-274.83"]
                ]}
                """);
        Map<LocalDate, MacroDataFetchClient.Turnover> turnovers =
                MacroDataFetchClient.parseTwseTurnoverJson(root);
        assertThat(turnovers).hasSize(1);
        assertThat(turnovers).containsKey(LocalDate.of(2026, 7, 2));
    }
}
