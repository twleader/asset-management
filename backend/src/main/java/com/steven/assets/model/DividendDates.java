package com.steven.assets.model;

import java.time.LocalDate;

/**
 * 「除息日／除權日」這一組欄位在 {@code backend} 內的<b>唯一</b>衍生算術來源
 * （Requirement 94 / Task 357.2c）。
 *
 * <p>Task 357 把配息事件的除息日與除權日拆成兩欄之後，backend 多處要從這兩欄推導出
 * 「錨定日」。這個推導<b>只能有一份實作</b>——{@code spec/steering/structure.md} §3.2
 * 鐵則 4 明文禁止同一 module 內再新增同義值的獨立實作，而 {@code backend} 是同一個
 * Maven artifact、同一個 Spring context，「沒有共用模組」在這裡不是免罪理由。</p>
 *
 * <p><b>放在 {@code model} 而不是 {@code service}</b>：這是領域規則、不依賴任何框架或 IO，
 * 而 entity（{@link StockDividendHistory}）與 service 層都要用它。放在 service 層會讓
 * {@code model → service} 反向依賴，違反 Clean Architecture 的「依賴一律由外向內」。</p>
 *
 * <p><b>{@code external-materials-service} 的
 * {@code com.steven.assets.externalmaterials.model.DividendDates} 是必須同步的對應實作</b>：
 * 該 module 的 {@code pom.xml} 不依賴 {@code backend}，全 repo 三個 {@code pom.xml} 無
 * aggregator、無共用程式模組，跨不過去，故比照 {@code structure.md} §3.2 鐵則 4 的
 * 「跨 Maven artifact」具名例外之五併存。<b>任一側改動語意，另一側必須同 commit 跟上</b>；
 * 兩側各以相同四個案例釘住（本側為 {@code DividendAnchorDateLeastTest}，
 * 對側為 {@code DividendDatesTest}）。</p>
 */
public final class DividendDates {

    private DividendDates() {}

    /**
     * 事件錨定日期＝除息日與除權日中<b>較早且非 null</b> 者（Task 357.2c）。
     *
     * <p>只有一個日期時恆等於該日期，故拆欄前的既有資料（除權日全為 {@code null}）
     * 行為逐位不變；兩者皆 {@code null} 時回 {@code null}（年度彙總列）。</p>
     *
     * <p><b>SQL 側的等價寫法是 {@code LEAST(ex_dividend_date, ex_rights_date)}，不是
     * {@code COALESCE}。</b>PostgreSQL 的 {@code LEAST} 忽略 {@code NULL}、全為 {@code NULL}
     * 才回 {@code NULL}，語意與本方法逐位相同；{@code COALESCE} 取的是「第一個非 null」，
     * 在<b>兩欄皆有值且除權日較早</b>時會取到較晚的除息日——那會讓區間過濾把一個實際
     * 落在視窗內的事件排除掉，正是 Task 357 要消滅的那種靜默消失，只是換個形狀。
     * 兩者只有在「至多一個非 null」或「純 null 檢查」時才等價。</p>
     */
    public static LocalDate anchorDate(LocalDate exDividendDate, LocalDate exRightsDate) {
        if (exDividendDate == null) return exRightsDate;
        if (exRightsDate == null) return exDividendDate;
        return exDividendDate.isAfter(exRightsDate) ? exRightsDate : exDividendDate;
    }
}
