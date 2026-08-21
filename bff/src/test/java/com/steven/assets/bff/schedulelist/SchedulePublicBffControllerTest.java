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
    @DisplayName("排程清單完整列出 21 個業務與 34 個外部行情工作")
    void 項目數正確() {
        assertThat(jobs()).hasSize(55);
        assertThat(jobs()).filteredOn(j -> "業務服務".equals(j.service())).hasSize(21);
        assertThat(jobs()).filteredOn(j -> "外部行情服務".equals(j.service())).hasSize(34);
    }

    @Test
    @DisplayName("美股歷史估值推導為台北週二至週六 07:30，且說明載明必須晚於美股收盤校正（Task 334）")
    void 美股歷史估值推導排程契約() {
        assertThat(jobs()).filteredOn(j -> "美股歷史估值序列推導".equals(j.name()))
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.service()).isEqualTo("外部行情服務");
                    assertThat(job.cron()).isEqualTo("0 30 7 * * TUE-SAT");
                    assertThat(job.zone()).isEqualTo("Asia/Taipei");
                    // 07:30 是硬約束（冬令時只剩 30 分鐘餘裕），說明漏了它就會有人「順手」往前調。
                    assertThat(job.description()).contains("SEC_DERIVED", "重算最近 30 個交易日", "台北 07:00");
                });
    }

    @Test
    @DisplayName("美股指數日線落後補救檢查為台北週二至週六 09:00／12:00，且不暗示檢查 TPEX（Task 346）")
    void 美股指數落後補救檢查契約() {
        assertThat(jobs()).filteredOn(j -> "美股指數日線落後補救檢查".equals(j.name()))
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.service()).isEqualTo("業務服務");
                    assertThat(job.cron()).isEqualTo("0 0 9,12 * * TUE-SAT");
                    assertThat(job.zone()).isEqualTo("Asia/Taipei");
                    assertThat(job.description()).contains("只回補確實落後");
                    assertThat(job.description()).doesNotContain("TPEX");
                });
    }

    @Test
    @DisplayName("台股個股與全量 code-keyed 回補文案精確反映兩分鐘與 TPEX 範圍")
    void 台股個股與全量回補契約() {
        assertThat(jobs()).filteredOn(j -> "台股個股即時價（盤中）".equals(j.name()))
                .singleElement().satisfies(job -> {
                    assertThat(job.schedule()).isEqualTo("交易日 09:00–13:30 每 2 分鐘");
                    assertThat(job.cron()).isEqualTo("0 0/2 9-13 * * MON-FRI");
                    assertThat(job.description()).contains("上市／上櫃", "TWSE MIS", "Redis");
                });
        assertThat(jobs()).filteredOn(j -> "櫃買／海外 code-keyed 指數日線回補".equals(j.name()))
                .singleElement().satisfies(job ->
                        assertThat(job.description()).contains("TPEX＋8 檔海外指數＋SP500TR＋TWSE 報酬指數增量"));
    }

    @Test
    @DisplayName("台股 ETF 淨值折溢價與股價錯開，每兩分鐘只打一個全市場 request（Task 349）")
    void 台股ETF淨值折溢價排程契約() {
        assertThat(jobs()).filteredOn(j -> "台股 ETF 淨值折溢價".equals(j.name()))
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.service()).isEqualTo("外部行情服務");
                    assertThat(job.category()).isEqualTo("ETF淨值");
                    assertThat(job.name()).isEqualTo("台股 ETF 淨值折溢價");
                    assertThat(job.description()).isEqualTo(
                            "盤中每 2 分鐘以一個證交所全市場 ETF 彙整檔 request，取即時預估淨值與折溢價寫入 Redis（Task 214／349）");
                    assertThat(job.schedule()).isEqualTo("交易日 09:01–13:29 每 2 分鐘");
                    assertThat(job.cron()).isEqualTo("0 1/2 9-13 * * MON-FRI");
                    assertThat(job.zone()).isEqualTo("Asia/Taipei");
                });
    }

    @Test
    @DisplayName("USD/TWD 2 秒排程明列全天 tick、銀行時段、single-flight 與 Redis-only")
    void 美元台幣即時排程契約() {
        assertThat(jobs()).filteredOn(j -> "USD/TWD 即時牌告（2 秒）".equals(j.name()))
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.cron()).isEqualTo("*/2 * * * * *");
                    assertThat(job.zone()).isEqualTo("Asia/Taipei");
                    assertThat(job.schedule()).contains("全天每 2 秒");
                    assertThat(job.description()).contains("台銀", "兆豐", "single-flight", "Redis", "不寫歷史 DB");
                });
    }

    @Test
    @DisplayName("Treasury 排程固定為台北週二至週六 07:00，且只新增一個 business job")
    void Treasury排程契約() {
        assertThat(jobs()).filteredOn(j -> "官方殖利率曲線刷新".equals(j.name()))
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.service()).isEqualTo("業務服務");
                    assertThat(job.cron()).isEqualTo("0 0 7 * * TUE-SAT");
                    assertThat(job.zone()).isEqualTo("Asia/Taipei");
                    assertThat(job.description()).contains("官方").contains("整批 fallback");
                });
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
