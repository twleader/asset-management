package com.steven.assets.bff.schedulelist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排程列表頁的說明文字守門（Requirement 55 / Task 272）。
 *
 * <p>Task 292 新增一個基本面排程；其餘既有項目不移除。表內任一含輸出格式的
 * {@code description} 仍必須保持雙格式真實描述。
 *
 * <p>「不動 {@code @Scheduled} ⇒ {@code JOBS} 不必動」這個推論在本專案已被否決過一次
 * （Requirement 48／Task 260：交易雷達那一筆的 description 在改了匯出行為後變成假的）。
 */
class SchedulePublicBffControllerTest {

    /** 透過公開查詢方法取清單（JOBS 是 private static，刻意不用反射）。 */
    private static List<ScheduledJobDto> jobs() {
        return new SchedulePublicBffController().list();
    }

    @Test
    @DisplayName("排程清單完整列出 19 個業務與 30 個外部行情工作")
    void 項目數正確() {
        assertThat(jobs()).hasSize(49);
        assertThat(jobs()).filteredOn(j -> "業務服務".equals(j.service())).hasSize(19);
        assertThat(jobs()).filteredOn(j -> "外部行情服務".equals(j.service())).hasSize(30);
    }

    @Test
    @DisplayName("每一條含 Excel 的說明都必須帶上新措辭——不得用「不含舊字樣」當判準")
    void 含格式字樣的說明都已改寫() {
        // 直覺會想「把舊文案 grep 到 0」，但那永遠做不到：新措辭本身就含「產出 JSON 與 Excel」，
        // 任何鎖舊文案的樣式改完後都還是會命中。故判準改為「含格式字樣的那幾條，是否全部帶上新措辭」。
        List<ScheduledJobDto> offenders = jobs().stream()
                .filter(j -> j.description() != null
                        && (j.description().contains("Excel") || j.description().contains("公開資訊 JSON")))
                .filter(j -> !j.description().contains("同時產出 JSON 與 Excel 兩份"))
                .toList();
        assertThat(offenders)
                .as("這幾條的說明在雙格式落地後是假的：%s",
                        offenders.stream().map(ScheduledJobDto::name).toList())
                .isEmpty();
    }

    @Test
    @DisplayName("交易日曆那一條不得再宣稱「以其格式（JSON／Excel）」——二選一已被拆掉")
    void 交易日曆不再宣稱二選一() {
        assertThat(jobs()).noneMatch(j -> j.description() != null
                && j.description().contains("以其格式（JSON／Excel）"));
    }
}
