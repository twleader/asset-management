package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Requirement 163／Task 452.10：SRPP 共用計算結果背景 producer（business-services 單一實例、無 ShedLock）。
 *
 * <p>每 5 分鐘（Asia/Taipei）先預先續期當年度台股假日快取（含週末，讓讀取端 cached-only 判斷全週可用），
 * 僅在「台股交易日確認開市」且 {@code 09:05 ≤ now < 14:00} 時，對「已支援規則包 × 有快照的 ACTIVE owner」
 * capture → calculate → publish。registry 為空時零計算。每個 owner 獨立 try/catch，失敗只 log owner id 與原因碼。
 * 全程不呼叫任何券商 API、不寫 Redis、不外呼行情 HTTP（行情只經既有 PriceQueryService 讀取）。
 */
@Slf4j
@Component
public class SrppDailyContextProducerScheduler {
    static final Duration CALENDAR_WARM_WINDOW = Duration.ofMinutes(6);
    private static final LocalTime FIRST_SLOT = LocalTime.of(9, 5);
    private static final LocalTime SECOND_SLOT = LocalTime.of(11, 40);
    private static final LocalTime END = LocalTime.of(14, 0);

    private final MarketDataService marketData;
    private final SrppPolicyRegistryService registry;
    private final AssetSnapshotRepository snapshots;
    private final AppUserRepository users;
    private final SrppSourceCapture capture;
    private final SrppPackagePublisher publisher;
    private final Clock clock;

    @Autowired
    public SrppDailyContextProducerScheduler(MarketDataService marketData, SrppPolicyRegistryService registry,
                                             AssetSnapshotRepository snapshots, AppUserRepository users,
                                             SrppSourceCapture capture, SrppPackagePublisher publisher) {
        this(marketData, registry, snapshots, users, capture, publisher, Clock.system(SrppTime.TW_ZONE));
    }

    SrppDailyContextProducerScheduler(MarketDataService marketData, SrppPolicyRegistryService registry,
                                      AssetSnapshotRepository snapshots, AppUserRepository users,
                                      SrppSourceCapture capture, SrppPackagePublisher publisher, Clock clock) {
        this.marketData = marketData;
        this.registry = registry;
        this.snapshots = snapshots;
        this.users = users;
        this.capture = capture;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(cron = "${srpp.producer-cron:0 */5 * * * *}", zone = "Asia/Taipei")
    public synchronized void produce() {
        try {
            marketData.warmTwHolidaysIfExpiringWithin(CALENDAR_WARM_WINDOW);
        } catch (RuntimeException e) {
            log.warn("SRPP producer 預先續期台股日曆失敗 reason={}", e.getClass().getSimpleName());
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(SrppTime.TW_ZONE));
        LocalDate today = now.toLocalDate();
        DayOfWeek dow = today.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return;
        Optional<Boolean> open;
        try {
            open = marketData.isTwTradingDayKnown(today);
        } catch (RuntimeException e) {
            open = Optional.empty();
        }
        if (!Optional.of(true).equals(open)) return;
        LocalTime time = now.toLocalTime();
        if (time.isBefore(FIRST_SLOT) || !time.isBefore(END)) return;
        String slot = slotFor(time);

        List<SupportedPolicy> policies = registry.supportedPolicies();
        if (policies.isEmpty()) return;

        Set<Long> active = users.findByStatus(AppUser.STATUS_ACTIVE).stream()
                .filter(AppUser::isActive)
                .map(AppUser::getId)
                .collect(Collectors.toSet());
        List<Long> owners = snapshots.findDistinctOwnerUserIds().stream()
                .filter(active::contains)
                .sorted()
                .toList();
        int published = 0;
        int skipped = 0;
        int failed = 0;
        for (SupportedPolicy policy : policies) {
            for (Long ownerId : owners) {
                try {
                    SrppCapture captured = capture.capture(ownerId, policy, today, slot);
                    SrppModuleCalculator.Result result =
                            SrppModuleCalculator.calculate(captured.assets(), policy, today);
                    if (result.rejectReason().isPresent()) {
                        skipped++;
                        log.warn("SRPP package 拒絕發布 owner={} reason={}", ownerId, result.rejectReason().get());
                        continue;
                    }
                    ObjectNode modules = result.modules();
                    if (publisher.publish(captured, modules).isPresent()) published++;
                    else skipped++;
                } catch (SrppRejectedException e) {
                    skipped++;
                    log.warn("SRPP package 拒絕發布 owner={} reason={}", ownerId, e.reasonCode());
                } catch (Exception e) {
                    failed++;
                    log.warn("SRPP package 產生失敗 owner={} reason=UNEXPECTED_ERROR type={}",
                            ownerId, e.getClass().getSimpleName());
                }
            }
        }
        log.info("SRPP producer 完成 slot={} date={} published={} skipped={} failed={}",
                slot, today, published, skipped, failed);
    }

    static String slotFor(LocalTime time) {
        return time.isBefore(SECOND_SLOT) ? "09:05" : "11:40";
    }
}
