package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FundDividendFetchClient;
import com.steven.assets.externalmaterials.client.FundDividendFetchClient.DividendRow;
import com.steven.assets.externalmaterials.service.FundNavSourceQuery.FundMasterRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 信託基金配息歷史每日抓取 (Requirement 20)。
 *
 * 09:05 Asia/Taipei（NAV 排程後 5 分鐘）— 對 fund_master.active=TRUE 的基金抓近 13 個月配息。
 * 配息資料慢更新（多為月底基準日）— 連抓 13 個月避免月初切換造成計算少算一個月。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundDividendPoller {

    private static final int MONTHS_BACK = 13;

    private final FundNavSourceQuery source;
    private final FundDividendFetchClient client;

    @Scheduled(cron = "0 5 9 * * *", zone = "Asia/Taipei")
    public void scheduledRefreshAll() {
        log.info("排程：信託基金配息每日刷新");
        refreshAll();
    }

    public RefreshSummary refreshAll() {
        var funds = source.findActiveFunds();
        int success = 0, fail = 0, written = 0;
        for (FundMasterRow f : funds) {
            try {
                List<DividendRow> rows = client.fetchRecent(
                        f.site(), f.fundclearOrgCode(), f.fundclearFundCode(),
                        f.fundclearClassCode(), MONTHS_BACK);
                if (rows.isEmpty()) {
                    log.info("配息抓不到 {} (site={}, org={}, fund={}, class={})",
                            f.fundCode(), f.site(), f.fundclearOrgCode(),
                            f.fundclearFundCode(), f.fundclearClassCode());
                    fail++;
                    continue;
                }
                for (DividendRow r : rows) {
                    source.upsertDividend(f.fundCode(), r.baseDate(), r.amount(),
                            r.currency(), r.frequency());
                    written++;
                }
                log.info("配息 upsert {}: {} 筆", f.fundCode(), rows.size());
                success++;
            } catch (Exception e) {
                log.warn("配息抓取異常 {}: {}", f.fundCode(), e.toString());
                fail++;
            }
        }
        log.info("配息刷新完成：success={} fail={} total={} written={}",
                success, fail, funds.size(), written);
        return new RefreshSummary(success, fail, funds.size(), written);
    }

    public record RefreshSummary(int success, int failed, int total, int written) {}
}
