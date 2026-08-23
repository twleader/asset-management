package com.steven.assets.externalmaterials.model;

import java.time.LocalDate;

/**
 * 「除息日／除權日」這一組欄位在 {@code external-materials-service} 內的<b>唯一</b>衍生
 * 算術來源（Requirement 94 / Task 357.2c）。
 *
 * <p>Task 357 把配息事件的除息日與除權日拆成兩欄之後，本服務有三條抓取路徑要從這兩欄推導
 * 「錨定日」：{@code client.DividendFetchClient}（歷史落地主路徑）、
 * {@code client.TaiwanOfficialDividendCalendarClient}（TWSE/TPEx 官方日曆的 dedupe key）與
 * {@code service.MarketDataFetchService}（唯讀顯示投影）。三者<b>只能有一份實作</b>——
 * 它們同屬 {@code asset-external-materials-service} 這一個 Maven artifact、同一個 Spring
 * context，{@code spec/steering/structure.md} §3.2 鐵則 4 明文寫過「同一 module 內寫成
 * 『無共用模組故無法收斂』是錯的」，故 package 不同不是免罪理由。</p>
 *
 * <p><b>放在 {@code model} 而不是 {@code client} 或 {@code service}</b>：這是純日期算術，
 * 不碰 HTTP、不碰 Redis／DB，兩個 package 都往內依賴它即可，不必為了一個純函式讓
 * {@code service} 去依賴 {@code client}（反之亦然）。它也不違反 §4.2 的「不持有業務邏輯」：
 * 這裡不知道 snapshot、不知道持倉，只定義抓回來的兩個日期要如何取錨。</p>
 *
 * <p><b>與 {@code backend} 的 {@code com.steven.assets.model.DividendDates} 是必須同步的
 * 對應實作</b>：{@code external-materials-service} 的 {@code pom.xml} 不依賴 {@code backend}，
 * 三個 {@code pom.xml} 之間沒有 aggregator、沒有共用程式模組，跨不過去，故比照
 * {@code structure.md} §3.2 的「跨 Maven artifact」具名例外併存（該例外之五已把本檔登記在案）。
 * <b>任一側改動語意，另一側必須同 commit 跟上</b>；兩側各有測試釘住相同四個案例
 * （本側為 {@code DividendDatesTest}，backend 側為 {@code DividendAnchorDateLeastTest}）。</p>
 */
public final class DividendDates {

    private DividendDates() {}

    /**
     * 事件錨定日期＝除息日與除權日中<b>較早且非 null</b> 者（Task 357.2c）。
     *
     * <p>只有一個日期時，結果恆等於拆欄前 {@code firstNonBlank(cashEx, stockEx)} 取到的值
     * （{@code firstNonBlank} 與 {@code min} 在單一非空值時結果相同），因此區間過濾、排序與
     * {@code year} 推導的既有行為逐位不變；兩個日期都有時取較早者，事件才不會落在區間外
     * 而漏抓。兩個都是 {@code null} 時回 {@code null}，即 357.2b 的「無效」判準。</p>
     *
     * <p><b>錨定日期不得回寫成落地的除息日／除權日。</b>落地值必須是真正的那一個日期，
     * 沒有就是 {@code null}——用錨定日期回填等於換一種方式繼續壓合。</p>
     *
     * <p>SQL 側的等價寫法是 {@code LEAST(...)} 而<b>不是</b> {@code COALESCE(...)}：後者取
     * 「第一個非 null」，兩欄皆有值且除權日較早時會取到較晚的除息日。</p>
     */
    public static LocalDate anchorDate(LocalDate exDividendDate, LocalDate exRightsDate) {
        if (exDividendDate == null) return exRightsDate;
        if (exRightsDate == null) return exDividendDate;
        return exDividendDate.isAfter(exRightsDate) ? exRightsDate : exDividendDate;
    }
}
