package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 每個美股交易日收盤後刷新 Treasury curve；固定 cron，不使用 crawler_schedule。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreasuryYieldRefreshScheduler {

    private final TreasuryYieldService treasuryYieldService;

    /** 美股收盤後：每日 07:00 Asia/Taipei（週二至週六）。 */
    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")
    public void refreshCurrentYear() {
        try {
            TreasuryYieldDto.RefreshSummary summary = treasuryYieldService.refresh(null);
            log.info("Treasury curve refresh 完成：year={}, fetched={}, inserted={}, noOp={}, complete={}, incomplete={}",
                    summary.year(), summary.fetchedBatches(), summary.insertedBatches(), summary.noOpBatches(),
                    summary.completeBatches(), summary.incompleteBatches());
        } catch (Exception e) {
            log.warn("Treasury curve refresh 失敗：{}", e.getMessage());
        }
    }
}
