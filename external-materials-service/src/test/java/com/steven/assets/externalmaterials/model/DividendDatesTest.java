package com.steven.assets.externalmaterials.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 357／357.2c：{@code external-materials-service} 內錨定日的<b>唯一</b>算術，
 * 以及「本 module 內只有這一份」這件事本身。
 *
 * <p>錨定日是 {@code min(除息日, 除權日)}，不是 {@code coalesce}——兩者只有在
 * 「至多一個非 null」時才等價，故本檔的關鍵案例刻意讓兩欄同時有值且<b>除權日較早</b>。
 * 與 backend 的 {@code com.steven.assets.model.DividendDates} 是必須同步的對應實作
 * （兩個 Maven artifact 之間沒有共用模組，見 {@code structure.md} §3.2 具名例外之五）。</p>
 */
class DividendDatesTest {

    private static final LocalDate EX_DIVIDEND = LocalDate.of(2026, 9, 5);
    private static final LocalDate EX_RIGHTS = LocalDate.of(2026, 8, 28);

    @Test
    @DisplayName("357.2c 兩欄皆有值時取較早者（coalesce 會取到較晚的除息日）")
    void picksTheEarlierDateWhenBothArePresent() {
        assertThat(DividendDates.anchorDate(EX_DIVIDEND, EX_RIGHTS)).isEqualTo(EX_RIGHTS);
        assertThat(DividendDates.anchorDate(EX_RIGHTS, EX_DIVIDEND))
                .as("min 不看欄位順序")
                .isEqualTo(EX_RIGHTS);
    }

    @Test
    @DisplayName("357.2c 只有一個日期時等於該日期；兩者皆 null 視為無日期")
    void degeneratesToTheSingleAvailableDate() {
        assertThat(DividendDates.anchorDate(EX_DIVIDEND, null)).isEqualTo(EX_DIVIDEND);
        assertThat(DividendDates.anchorDate(null, EX_RIGHTS)).isEqualTo(EX_RIGHTS);
        assertThat(DividendDates.anchorDate(null, null)).isNull();
    }

    /**
     * 三條抓取路徑（{@code client.DividendFetchClient}、
     * {@code client.TaiwanOfficialDividendCalendarClient}、
     * {@code service.MarketDataFetchService}）都必須委派本類別，不得各留一份四行實作。
     * 它們同屬一個 Maven artifact、同一個 Spring context，「無共用模組」在這裡不成立。
     */
    @Test
    @DisplayName("357.2c 三條抓取路徑的私有 anchor helper 都回傳與本類別相同的結果")
    void everyFetchPathDelegatesToThisArithmetic() throws Exception {
        Method fetchClient = privateAnchor(
                Class.forName("com.steven.assets.externalmaterials.client.DividendFetchClient"),
                "anchorDate", LocalDate.class, LocalDate.class);
        Method marketData = privateAnchor(
                Class.forName("com.steven.assets.externalmaterials.service.MarketDataFetchService"),
                "anchorDividendDate", LocalDate.class, LocalDate.class);

        for (Method method : List.of(fetchClient, marketData)) {
            assertThat(method.invoke(null, EX_DIVIDEND, EX_RIGHTS))
                    .as("%s 必須取較早者", method.getDeclaringClass().getSimpleName())
                    .isEqualTo(EX_RIGHTS);
            assertThat(method.invoke(null, EX_DIVIDEND, null))
                    .as("%s 只有除息日時逐位不變", method.getDeclaringClass().getSimpleName())
                    .isEqualTo(EX_DIVIDEND);
            assertThat(method.invoke(null, null, EX_RIGHTS))
                    .as("%s 只有除權日（純配股）", method.getDeclaringClass().getSimpleName())
                    .isEqualTo(EX_RIGHTS);
            assertThat(method.invoke(null, null, null)).isNull();
        }
    }

    private static Method privateAnchor(Class<?> owner, String name, Class<?>... params)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }
}
