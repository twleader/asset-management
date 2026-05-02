package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FundNavFetchClient;
import com.steven.assets.externalmaterials.client.FundNavFetchClient.NavResult;
import com.steven.assets.externalmaterials.service.FundNavSourceQuery.FundMasterRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 信託基金 NAV 每日抓取（Requirement 19）。
 * 09:00 Asia/Taipei 全抓 fund_master.active=TRUE 的所有基金，寫入 fund_nav。
 *
 * 設計考量：
 * - 失敗單支不影響其他支
 * - upsert 同 (fund_code, nav_date) 視為覆寫，可重複跑
 * - 抓不到就跳過、保留 fund_nav 既有最後一筆，由前端判斷「資料過時 N 天」
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundNavPoller {

    private final FundNavSourceQuery source;
    private final FundNavFetchClient client;

    /** 每日 09:00 (Asia/Taipei) 全抓。 */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Taipei")
    public void scheduledRefreshAll() {
        log.info("排程：信託基金 NAV 每日刷新");
        refreshAll();
    }

    /**
     * 同步全抓：給 InternalPriceController POST /internal/fund-nav/refresh 用。
     * 回傳成功 / 失敗筆數。
     */
    public RefreshSummary refreshAll() {
        var funds = source.findActiveFunds();
        int success = 0, fail = 0;
        for (FundMasterRow f : funds) {
            try {
                Optional<NavResult> latest = client.fetchLatest(
                        f.site(), f.fundclearOrgCode(), f.fundclearFundCode(), f.fundclearClassCode());
                if (latest.isPresent()) {
                    NavResult r = latest.get();
                    source.upsertNav(f.fundCode(), r.navDate(), r.nav(), "FUNDCLEAR");
                    log.info("NAV upsert {} {} = {} ({})", f.fundCode(), r.navDate(), r.nav(), f.currency());
                    success++;
                } else {
                    log.warn("NAV 抓不到 {} (site={}, org={}, fund={}, class={})",
                            f.fundCode(), f.site(), f.fundclearOrgCode(),
                            f.fundclearFundCode(), f.fundclearClassCode());
                    fail++;
                }
            } catch (Exception e) {
                log.warn("NAV 抓取異常 {}: {}", f.fundCode(), e.toString());
                fail++;
            }
        }
        log.info("NAV 刷新完成：success={} fail={} total={}", success, fail, funds.size());
        return new RefreshSummary(success, fail, funds.size());
    }

    public record RefreshSummary(int success, int failed, int total) {}
}
