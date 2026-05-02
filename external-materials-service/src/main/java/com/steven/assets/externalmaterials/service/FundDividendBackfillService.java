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
            int written = backfillOne(f, years);
            if (written >= 0) { fundOk++; totalRows += written; }
            else fundFail++;
        }
        log.info("配息 backfill 完成：基金 OK={} 失敗={} 總筆數={}", fundOk, fundFail, totalRows);
        return new BackfillSummary(fundOk, fundFail, funds.size(), totalRows);
    }

    /** 分年呼叫 FundClear（單次大範圍會被 API 內部分頁 cap，須逐年抓）。 */
    private int backfillOne(FundMasterRow f, int years) {
        int written = 0;
        try {
            for (int y = 0; y < years; y++) {
                // fetchRecent 接 monthsBack；用 12 配合每段一年；以「往前 y*12 ~ y*12+12」為單位
                // FundClear 的 API 是 baseBeginDate ~ baseEndDate，所以實作時改 fetchRecent 簽名直接傳 from~to 也可
                // 這裡簡化：每段呼叫 fetchRecent(monthsBack=12) 12 次共 12 年，超過 years 跳出
                if (y >= years) break;
                int monthsBackTo = y * 12;
                int monthsBackFrom = (y + 1) * 12;
                List<DividendRow> rows = client.fetchRange(
                        f.site(), f.fundclearOrgCode(), f.fundclearFundCode(), f.fundclearClassCode(),
                        monthsBackFrom, monthsBackTo);
                if (rows == null) continue;
                for (DividendRow r : rows) {
                    source.upsertDividend(f.fundCode(), r.baseDate(), r.amount(),
                            r.currency(), r.frequency());
                    written++;
                }
            }
            log.info("配息 backfill {} {} 年共 {} 筆", f.fundCode(), years, written);
            return written;
        } catch (Exception e) {
            log.warn("配息 backfill 異常 {}: {}", f.fundCode(), e.toString());
            return -1;
        }
    }

    public record BackfillSummary(int fundOk, int fundFail, int total, int rowsWritten) {}
}
