package com.steven.assets.service;

import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertGroup;
import com.steven.assets.repository.StockAlertGroupRecipientRepository;
import com.steven.assets.repository.StockAlertGroupRepository;
import com.steven.assets.repository.StockAlertRecipientRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 複合條件群組的合併文案 {@code StockAlertService.buildGroupLabel}（Requirement 16 / Task 253）。
 *
 * <p>警示頁、觀察頁、email digest、補發四條路徑都用這一支產文案，
 * 分隔符的字面值一旦分歧就會有一邊對不上，所以這裡一律<b>斷言整個字串逐字相等</b>
 * ——用 {@code contains("且")} 會讓寫成沒有前後空白的 {@code "且"} 也通過，
 * 而其他端假設的是 {@code " 且 "}（半形空白 + 且 + 半形空白）。
 */
class StockAlertGroupLabelTest {

    /** 產一個只有判定 / 文案欄位的成員；buildLabel 只讀 alertType / maPeriod / threshold。 */
    private static StockAlert member(String alertType, Integer maPeriod, String threshold) {
        return StockAlert.builder()
                .alertType(alertType)
                .maPeriod(maPeriod)
                .threshold(new BigDecimal(threshold))
                .build();
    }

    @Test
    @DisplayName("兩個條件以「 且 」串接，順序即傳入順序（逐字相等）")
    void twoConditionsJoined() {
        assertEquals("低於季線 10% 且 K 值低於 15",
                StockAlertService.buildGroupLabel(
                        List.of(member("MA_BELOW_PCT", 60, "10"),
                                member("KD_BELOW", null, "15")),
                        null));
    }

    @Test
    @DisplayName("三個條件有兩個「 且 」分隔符")
    void threeConditionsJoined() {
        assertEquals("低於季線 10% 且 K 值低於 15 且 股價高於 100",
                StockAlertService.buildGroupLabel(
                        List.of(member("MA_BELOW_PCT", 60, "10"),
                                member("KD_BELOW", null, "15"),
                                member("PRICE_ABOVE", null, "100")),
                        null));
    }

    @Test
    @DisplayName("單一條件無分隔符，回傳值等於該條件自己的 label")
    void singleConditionHasNoSeparator() {
        StockAlert only = member("KD_BELOW", null, "15");
        assertEquals(StockAlertService.buildLabel(only, null),
                StockAlertService.buildGroupLabel(List.of(only), null));
        assertEquals("K 值低於 15", StockAlertService.buildGroupLabel(List.of(only), null));
    }

    @Test
    @DisplayName("空 list 回空字串、不丟例外（成員被外力清掉的孤兒群組不該讓整份清單載入失敗）")
    void emptyMembersReturnsEmptyString() {
        assertEquals("", StockAlertService.buildGroupLabel(List.of(), null));
        assertEquals("", StockAlertService.buildGroupLabel(null, null));
    }

    @Test
    @DisplayName("非 0% MA 成員維持換算觸發價，其餘成員不受影響")
    void maMemberGetsTriggerPriceSuffix() {
        // 季線 102.32 × (1 - 10%) = 92.088 → 四捨五入到小數 2 位 = 92.09
        TechnicalIndicatorService.FullIndicators ind = new TechnicalIndicatorService.FullIndicators(
                new BigDecimal("55.00"),    // 月線（本例的成員用不到，故意給不同值以免抓錯均線也剛好通過）
                new BigDecimal("102.32"),   // 季線
                new BigDecimal("77.00"),    // 年線
                new BigDecimal("12.3"), new BigDecimal("14.5"),
                new BigDecimal("11.0"), new BigDecimal("13.0"), null,
                TechnicalIndicatorService.ExtendedIndicators.EMPTY, null);

        assertEquals("低於季線 10%（92.09） 且 K 值低於 15",
                StockAlertService.buildGroupLabel(
                        List.of(member("MA_BELOW_PCT", 60, "10"),
                                member("KD_BELOW", null, "15")),
                        ind));
    }

    @Test
    @DisplayName("threshold=0 的 MA 條件附均線價格，不顯示 0%")
    void zeroThresholdMaGetsPriceSuffix() {
        TechnicalIndicatorService.FullIndicators ind = new TechnicalIndicatorService.FullIndicators(
                new BigDecimal("55.00"), new BigDecimal("102.32"), new BigDecimal("77.00"),
                new BigDecimal("12.3"), new BigDecimal("14.5"),
                new BigDecimal("11.0"), new BigDecimal("13.0"), null,
                TechnicalIndicatorService.ExtendedIndicators.EMPTY, null);

        assertEquals("低於季線（102.32） 且 K 值低於 15",
                StockAlertService.buildGroupLabel(
                        List.of(member("MA_BELOW_PCT", 60, "0"),
                                member("KD_BELOW", null, "15")),
                        ind));
    }

    @ParameterizedTest
    @CsvSource({
            "MA_ABOVE_PCT,20,高於月線（55.01）",
            "MA_BELOW_PCT,20,低於月線（55.01）",
            "MA_ABOVE_PCT,60,高於季線（102.32）",
            "MA_BELOW_PCT,60,低於季線（102.32）",
            "MA_ABOVE_PCT,240,高於年線（77）",
            "MA_BELOW_PCT,240,低於年線（77）"
    })
    void zeroPercentUsesMatchingMaWithRoundingAndNoTrailingZeros(String type, int period, String expected) {
        assertEquals(expected, StockAlertService.buildLabel(member(type, period, "0.00"),
                indicators(new BigDecimal("55.005"), new BigDecimal("102.320"), new BigDecimal("77.000"))));
    }

    @Test
    void missingOrZeroMaRetainsPlainLabelForBothDirections() {
        for (String type : List.of("MA_ABOVE_PCT", "MA_BELOW_PCT")) {
            String direction = type.equals("MA_ABOVE_PCT") ? "高於" : "低於";
            for (int period : List.of(20, 60, 240)) {
                String periodName = period == 20 ? "月線" : period == 60 ? "季線" : "年線";
                StockAlert alert = member(type, period, "0");
                String expected = direction + periodName;
                assertEquals(expected, StockAlertService.buildLabel(alert, null));
                assertEquals(expected, StockAlertService.buildLabel(alert, indicators(null, null, null)));
                assertEquals(expected, StockAlertService.buildLabel(alert,
                        indicators(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
            }
        }
    }

    @Test
    void nonzeroAbovePercentRetainsExistingFormatting() {
        assertEquals("高於季線 5%（107.44）", StockAlertService.buildLabel(
                member("MA_ABOVE_PCT", 60, "5"),
                indicators(new BigDecimal("55"), new BigDecimal("102.32"), new BigDecimal("77"))));
    }

    private static TechnicalIndicatorService.FullIndicators indicators(BigDecimal monthly, BigDecimal quarterly,
                                                                       BigDecimal annual) {
        return new TechnicalIndicatorService.FullIndicators(monthly, quarterly, annual,
                null, null, null, null, null, TechnicalIndicatorService.ExtendedIndicators.EMPTY, null);
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class ResponseMapping {
        @Mock StockAlertRepository alertRepo;
        @Mock StockPriceHistoryRepository historyRepo;
        @Mock StockRepository stockMasterRepo;
        @Mock TechnicalIndicatorService indicatorService;
        @Mock StockAlertRecipientRepository recipientLinkRepo;
        @Mock StockAlertGroupRepository groupRepo;
        @Mock StockAlertGroupRecipientRepository groupRecipientRepo;
        @InjectMocks StockAlertService service;

        private final TechnicalIndicatorService.FullIndicators currentIndicators = indicators(
                new BigDecimal("55.005"), new BigDecimal("102.32"), new BigDecimal("77"));

        private StockAlert single(String type, Integer period, String threshold) {
            StockAlert alert = member(type, period, threshold);
            alert.setId(10L);
            alert.setStockCode("0050");
            alert.setMarket("台股");
            return alert;
        }

        private void groupWith(List<StockAlert> members) {
            StockAlertGroup group = StockAlertGroup.builder().id(20L).stockCode("0050").market("台股").build();
            when(groupRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(group));
            when(alertRepo.findByGroupIdOrderByDisplayOrderAsc(20L)).thenReturn(members);
        }

        @Test
        void standaloneZeroPercentLoadsMaAndReturnsPrice() {
            when(alertRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(
                    List.of(single("MA_BELOW_PCT", 20, "0")));
            when(indicatorService.computeAll("0050", "台股")).thenReturn(currentIndicators);

            assertEquals("低於月線（55.01）", service.findAll().getFirst().conditionLabel());
            verify(indicatorService).computeAll("0050", "台股");
            verifyNoMoreInteractions(indicatorService);
        }

        @Test
        void zeroPercentGroupSharesOneIndicatorReadForMergedAndMemberLabels() {
            groupWith(List.of(member("MA_BELOW_PCT", 60, "0"), member("MA_ABOVE_PCT", 20, "0")));
            when(indicatorService.computeAll("0050", "台股")).thenReturn(currentIndicators);

            var response = service.findAll().getFirst();

            assertEquals("低於季線（102.32） 且 高於月線（55.01）", response.conditionLabel());
            assertEquals(List.of("低於季線（102.32）", "高於月線（55.01）"),
                    response.conditions().stream().map(c -> c.label()).toList());
            verify(indicatorService).computeAll("0050", "台股");
            verifyNoMoreInteractions(indicatorService);
        }

        @Test
        void nonMaSingleAndGroupDoNotReadIndicators() {
            when(alertRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(
                    List.of(single("PRICE_ABOVE", null, "100")));
            groupWith(List.of(member("KD_BELOW", null, "15"), member("PRICE_BELOW", null, "90")));

            var response = service.findAll();

            assertEquals(List.of("股價高於 100", "K 值低於 15 且 股價低於 90"),
                    response.stream().map(r -> r.conditionLabel()).toList());
            verifyNoInteractions(indicatorService);
        }

        @Test
        void indicatorFailureKeepsSingleAndGroupReadable() {
            when(alertRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(
                    List.of(single("MA_ABOVE_PCT", 240, "0")));
            groupWith(List.of(member("MA_BELOW_PCT", 60, "0"), member("KD_BELOW", null, "15")));
            when(indicatorService.computeAll("0050", "台股")).thenThrow(new IllegalStateException("unavailable"));

            var response = service.findAll();

            assertEquals("高於年線", response.get(0).conditionLabel());
            assertEquals("低於季線 且 K 值低於 15", response.get(1).conditionLabel());
            assertEquals(List.of("低於季線", "K 值低於 15"),
                    response.get(1).conditions().stream().map(c -> c.label()).toList());
        }
    }
}
