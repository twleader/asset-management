package com.steven.assets.externalmaterials.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * CME Globex 交易時段判定（WTI／布蘭特／COMEX 黃金；Requirement 77）。
 *
 * 三者同屬 CME Globex 週期：週日 18:00 America/New_York 開盤 → 週五 17:00 收盤，其間每日
 * 17:00–18:00 為維護休息（週六全日不在時段）。
 *
 * <p><b>刻意不建 CME 假日行事曆，也刻意不挪用既有 {@link MarketCalendar}</b>——那是台／美／英
 * <b>股市</b>行事曆，期貨在多數美股假日照常交易，只是提前收盤，挪用會判錯。假日照 tick，
 * 但來源時間戳不會推進，由 {@link CommoditySpotCacheWriter} 的 freshness 守門吸收成
 * {@code STALE}，不需要本 policy 額外處理。
 */
@Component
public class CommodityTradingSessionPolicy {

    static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalTime MAINTENANCE_START = LocalTime.of(17, 0);
    private static final LocalTime MAINTENANCE_END = LocalTime.of(18, 0);

    private final Clock clock;

    @Autowired
    public CommodityTradingSessionPolicy() {
        this(Clock.system(NY));
    }

    CommodityTradingSessionPolicy(Clock clock) {
        this.clock = clock.withZone(NY);
    }

    /** 現在（America/New_York 當地時間）是否在交易時段內。 */
    public boolean inSession() {
        return inSession(ZonedDateTime.now(clock));
    }

    /** package-private：供測試以固定時刻驅動，時間來源必須可注入（見任務檔 337.1）。 */
    boolean inSession(ZonedDateTime rawNow) {
        ZonedDateTime now = rawNow.withZoneSameInstant(NY);
        LocalTime time = now.toLocalTime();
        return switch (now.getDayOfWeek()) {
            case SATURDAY -> false;
            // 週日：< 18:00 不在時段；>= 18:00 在時段。
            case SUNDAY -> !time.isBefore(MAINTENANCE_END);
            // 週五：< 17:00 在時段；>= 17:00 不在時段（收盤後不重開）。
            case FRIDAY -> time.isBefore(MAINTENANCE_START);
            // 週一～週四：17:00 <= t < 18:00 不在時段；其餘在時段。
            default -> time.isBefore(MAINTENANCE_START) || !time.isBefore(MAINTENANCE_END);
        };
    }
}
