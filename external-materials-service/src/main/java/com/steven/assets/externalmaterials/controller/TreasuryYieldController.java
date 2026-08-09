package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.client.TreasuryYieldFetchClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Docker internal API：抓取指定年度的美債殖利率 curve batches。 */
@RestController
@RequestMapping("/internal/macro/treasury-yield")
@RequiredArgsConstructor
public class TreasuryYieldController {

    private final TreasuryYieldFetchClient fetchClient;

    /**
     * 官方年度 CSV 優先；官方不可用／partial 時才回附 Yahoo 四 tenor 的完整 fallback batch。
     * {@code year} 只控制資料年度，不得以 tenor 改變 upstream fetch。
     */
    @GetMapping
    public List<TreasuryYieldFetchClient.CurveBatch> fetchYear(@RequestParam int year) {
        return fetchClient.fetchYear(year);
    }
}
