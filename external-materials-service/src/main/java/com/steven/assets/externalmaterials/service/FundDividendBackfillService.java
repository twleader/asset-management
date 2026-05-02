package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FundDividendFetchClient;
import com.steven.assets.externalmaterials.client.FundDividendFetchClient.DividendRow;
import com.steven.assets.externalmaterials.service.FundNavSourceQuery.FundMasterRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 信託基金歷史配息回補（Requirement 21）。
 * 配息資料量小（月配最多每月一筆），一次抓 N 年也不會爆 response，故不分段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundDividendBackfillService {

    private final FundNavSourceQuery source;
    private final FundDividendFetchClient client;

    public BackfillSummary backfillAll(int years) {
        var funds = source.findActiveFunds();
        int totalRows = 0, fundOk = 0, fundFail = 0;
        for (FundMasterRow f : funds) {
            try {
                List<DividendRow> rows = client.fetchRecent(
                        f.site(), f.fundclearOrgCode(), f.fundclearFundCode(),
                        f.fundclearClassCode(), years * 12);
                if (rows.isEmpty()) {
                    log.info("配息 backfill {} 0 筆（累積型 / 無資料）", f.fundCode());
                    fundOk++;
                    continue;
                }
                for (DividendRow r : rows) {
                    source.upsertDividend(f.fundCode(), r.baseDate(), r.amount(),
                            r.currency(), r.frequency());
                    totalRows++;
                }
                log.info("配息 backfill {} {} 年共 {} 筆", f.fundCode(), years, rows.size());
                fundOk++;
            } catch (Exception e) {
                log.warn("配息 backfill 異常 {}: {}", f.fundCode(), e.toString());
                fundFail++;
            }
        }
        log.info("配息 backfill 完成：基金 OK={} 失敗={} 總筆數={}", fundOk, fundFail, totalRows);
        return new BackfillSummary(fundOk, fundFail, funds.size(), totalRows);
    }

    public record BackfillSummary(int fundOk, int fundFail, int total, int rowsWritten) {}
}
