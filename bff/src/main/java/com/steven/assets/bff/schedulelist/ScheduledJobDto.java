package com.steven.assets.bff.schedulelist;

/**
 * 排程列表頁（「公開資訊」分組，Requirement 36）單筆排程資訊。
 *
 * <p>唯讀資訊展示用的不可變 record。清單為 {@link SchedulePublicBffController} 內建靜態資料，
 * 對應 {@code business-services} 與 {@code external-materials-service} 兩服務中的 {@code @Scheduled} 方法。
 *
 * @param service     所屬服務（業務服務 / 外部行情服務）
 * @param category    分類（即時行情 / 收盤落庫 / 大盤指數 / 匯率 / 基金 / 股利 / 備份 …）
 * @param name        中文名稱
 * @param description 白話說明
 * @param schedule    白話執行時機（如「交易日 07:30」）
 * @param cron        cron 表達式（或 fixedDelay 描述）
 * @param zone        時區（如 Asia/Taipei；fixedDelay 者為空字串）
 */
public record ScheduledJobDto(
        String service,
        String category,
        String name,
        String description,
        String schedule,
        String cron,
        String zone
) {
}
