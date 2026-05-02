package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FundNavFetchClient;
import com.steven.assets.externalmaterials.client.FundNavFetchClient.NavResult;
import com.steven.assets.externalmaterials.service.FundNavSourceQuery.FundMasterRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 信託基金歷史 NAV 回補（Requirement 21）。
 * 分年抓取避免單次 response 過大；對 fund_master.active=TRUE 全部基金跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundNavBackfillService {

    private final FundNavSourceQuery source;
    private final FundNavFetchClient client;

    public BackfillSummary backfillAll(int years) {
        var funds = source.findActiveFunds();
        int totalRows = 0, fundOk = 0, fundFail = 0;
        for (FundMasterRow f : funds) {
            int rows = backfillOne(f, years);
            if (rows > 0) { fundOk++; totalRows += rows; }
            else fundFail++;
        }
        log.info("NAV backfill 完成：基金 OK={} 失敗={} 總筆數={}", fundOk, fundFail, totalRows);
        return new BackfillSummary(fundOk, fundFail, funds.size(), totalRows);
    }

    private int backfillOne(FundMasterRow f, int years) {
        LocalDate today = LocalDate.now();
        int total = 0;
        for (int y = 0; y < years; y++) {
            LocalDate to = today.minusYears(y);
            LocalDate from = today.minusYears(y + 1).plusDays(1);
            try {
                Optional<List<NavResult>> maybe = client.fetchRange(
                        f.site(), f.fundclearOrgCode(), f.fundclearFundCode(), f.fundclearClassCode(),
                        from, to);
                if (maybe.isEmpty()) continue;
                for (NavResult r : maybe.get()) {
                    source.upsertNav(f.fundCode(), r.navDate(), r.nav(), "FUNDCLEAR");
                    total++;
                }
            } catch (Exception e) {
                log.warn("NAV backfill 異常 {} {}~{}: {}", f.fundCode(), from, to, e.toString());
            }
        }
        log.info("NAV backfill {} {} 年共 {} 筆", f.fundCode(), years, total);
        return total;
    }

    public record BackfillSummary(int fundOk, int fundFail, int total, int rowsWritten) {}
}
