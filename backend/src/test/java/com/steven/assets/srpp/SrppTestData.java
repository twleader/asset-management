package com.steven.assets.srpp;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.dto.LatestAssetsDto;
import com.steven.assets.service.StockPriceService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** 測試用：以列資料組出自洽的 LatestAssetsDto.Response，並可覆寫任一彙總欄位製造不一致。 */
final class SrppTestData {
    static final LocalDate DATE = LocalDate.of(2026, 9, 24);

    final List<AssetSnapshotDto.DepositResponse> deposits = new ArrayList<>();
    final List<AssetSnapshotDto.FundResponse> funds = new ArrayList<>();
    final List<AssetSnapshotDto.StockResponse> stocks = new ArrayList<>();
    final List<StockPriceService.LiveStockItem> live = new ArrayList<>();
    Long snapshotId = 1L;
    Long liveSnapshotId;
    String liveSnapshotDate;
    BigDecimal totalDeposit, totalFundValue, totalStockValue, totalAssets, estimatedAnnualDividend;
    BigDecimal liveTotalDeposit, liveTotalFundValue, liveStockValue, liveTotalAssets;
    boolean targetPriceComplete = true;
    /** true 時快照 estimatedAnnualDividend 為 null（模擬舊快照缺值）。 */
    boolean estimatedAnnualDividendNull = false;
    String notes = "note";

    static BigDecimal d(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    SrppTestData deposit(long id, Long bankId, String bankName, String type, String amount, String original,
                         String currency, String rate) {
        deposits.add(new AssetSnapshotDto.DepositResponse(id, bankId, bankName, type, type + "顯示", d(amount),
                d(original), currency, d(rate), null, "備註", "MANUAL", null));
        return this;
    }

    SrppTestData fund(long id, String code, String value, String dividend) {
        funds.add(new AssetSnapshotDto.FundResponse(id, "基金" + id, code, 3L, "銀行", d(value), d(value), d("10"),
                d(dividend), null, BigDecimal.ZERO, BigDecimal.ZERO));
        return this;
    }

    /** 同時新增快照股票列與對應 live 列（liveValue 預設等於 currentValue）。 */
    SrppTestData stock(long id, String market, String code, String shares, String value, String dividend) {
        return stock(id, market, code, shares, value, dividend, value, null);
    }

    SrppTestData stock(long id, String market, String code, String shares, String value, String dividend,
                       String liveValue, Instant updatedAt) {
        stocks.add(new AssetSnapshotDto.StockResponse(id, code, "名稱" + id, market, 7L, "券商", d(shares),
                d(value), d(value), d(value), BigDecimal.ZERO, BigDecimal.ZERO, d(dividend), null, "TWD", null,
                null, null, null, (int) id));
        live.add(new StockPriceService.LiveStockItem(code, "名稱" + id, market, d(shares), d("1"), d(liveValue),
                false, DATE.toString(), null, null, null, "OK", id, "FUBON", DATE.toString(),
                "TARGET_SESSION_PRICE", updatedAt));
        return this;
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().map(v -> v == null ? BigDecimal.ZERO : v).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    AssetSnapshotDto.SnapshotDetailResponse snapshot() {
        BigDecimal dep = totalDeposit != null ? totalDeposit : sum(deposits.stream().map(AssetSnapshotDto.DepositResponse::amount).toList());
        BigDecimal fnd = totalFundValue != null ? totalFundValue : sum(funds.stream().map(AssetSnapshotDto.FundResponse::currentValue).toList());
        BigDecimal stk = totalStockValue != null ? totalStockValue : sum(stocks.stream().map(AssetSnapshotDto.StockResponse::currentValue).toList());
        BigDecimal total = totalAssets != null ? totalAssets : dep.add(fnd).add(stk);
        BigDecimal income = estimatedAnnualDividendNull ? null
                : estimatedAnnualDividend != null ? estimatedAnnualDividend
                : sum(stocks.stream().map(AssetSnapshotDto.StockResponse::estimatedDividend).toList())
                .add(sum(funds.stream().map(AssetSnapshotDto.FundResponse::estimatedDividend).toList()))
                .add(sum(deposits.stream().map(SrppModuleCalculator::depositInterest).toList()));
        return new AssetSnapshotDto.SnapshotDetailResponse(snapshotId, DATE, d("32.5"), dep, fnd, fnd, stk, stk, total,
                income, BigDecimal.ZERO, notes, List.copyOf(deposits), List.copyOf(funds), List.copyOf(stocks));
    }

    LatestAssetsDto.Response build() {
        AssetSnapshotDto.SnapshotDetailResponse snapshot = snapshot();
        BigDecimal dep = liveTotalDeposit != null ? liveTotalDeposit : snapshot.totalDeposit();
        BigDecimal fnd = liveTotalFundValue != null ? liveTotalFundValue : snapshot.totalFundValue();
        BigDecimal stk = liveStockValue != null ? liveStockValue
                : sum(live.stream().map(StockPriceService.LiveStockItem::liveValue).toList());
        BigDecimal total = liveTotalAssets != null ? liveTotalAssets : dep.add(fnd).add(stk);
        StockPriceService.LiveAssetsResponse liveAssets = new StockPriceService.LiveAssetsResponse(
                liveSnapshotId != null ? liveSnapshotId : snapshotId,
                liveSnapshotDate != null ? liveSnapshotDate : DATE.toString(),
                d("32.5"), dep, fnd, stk, total, List.copyOf(live), true, false, false, "2026-09-24T09:04:00");
        return new LatestAssetsDto.Response(Instant.parse("2026-09-24T01:05:00Z"),
                "TARGET_SESSION_WITH_EXPLICIT_FALLBACK", targetPriceComplete, Map.of(), snapshot, liveAssets);
    }

    static SupportedPolicy policy(Map<String, String> targets) {
        SortedMap<String, BigDecimal> parsed = new TreeMap<>();
        StringBuilder json = new StringBuilder("{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{");
        boolean first = true;
        for (var e : new TreeMap<>(targets).entrySet()) {
            parsed.put(e.getKey(), new BigDecimal(e.getValue()));
            if (!first) json.append(',');
            first = false;
            json.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        json.append("}}");
        SrppPolicyDocument doc = SrppPolicyDocument.parse(json.toString());
        return new SupportedPolicy("a".repeat(64), SrppFormulaCatalog.FORMULA_VERSION, SrppJcs.hash(doc.document()),
                SrppFormulaCatalog.formulaSetSha256(), doc.document(), SrppFormulaCatalog.manifest(), parsed,
                Instant.parse("2026-09-20T00:00:00Z"));
    }

    /** proposal 合成範例：存款 3,000,000（定存 2,000,000 年利率 1.5%、活存 1,000,000）與股票 2,000,000。 */
    static SrppTestData proposal() {
        return new SrppTestData()
                .deposit(1, 10L, "合成範例銀行", "TERM", "2000000", "2000000", "TWD", "1.5")
                .deposit(2, 10L, "合成範例銀行", "DEMAND", "1000000", "1000000", "TWD", null)
                .stock(1, "台股", "0050", "10000", "2000000", "60000");
    }
}
