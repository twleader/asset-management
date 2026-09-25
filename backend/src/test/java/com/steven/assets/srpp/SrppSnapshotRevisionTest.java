package com.steven.assets.srpp;

import com.steven.assets.dto.AssetSnapshotDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 163／Task 452.6：SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1 golden。
 * 改動投影即須同步改 manifest 的 revisionProjection 版本標記與本 golden。
 */
class SrppSnapshotRevisionTest {
    static final String GOLDEN = "snapshot-1-2eb184f050f9fb16ca9e9e1daffd593deaa241fec2403d78fbbe61fabf6ba20d";

    private static AssetSnapshotDto.SnapshotDetailResponse base() {
        return new SrppTestData()
                .deposit(2, 10L, "銀行", "DEMAND", "1000000.00", "1000000", "TWD", null)
                .deposit(1, null, null, "買股待付款", "-31004", null, "TRANSIT_TWD", "0")
                .fund(1, "F001", "100000.50", null)
                .stock(1, "台股", "0050", "2.00000", "2000000", "60000")
                .snapshot();
    }

    @Test
    void goldenRevisionIsPinned() {
        assertThat(SrppSnapshotRevision.of(base())).isEqualTo(GOLDEN);
        assertThat(SrppJcs.canonicalize(SrppSnapshotRevision.projection(base())))
                .startsWith("{\"deposits\":[{\"amount\":\"-31004\",")
                .contains("\"shares\":\"2\"")
                .doesNotContain("備註").doesNotContain("bankDisplayName").doesNotContain("stockName")
                .doesNotContain("displayOrder").doesNotContain("notes").doesNotContain("depositDisplayName")
                .contains("\"fundName\":\"基金1\"");
    }

    @Test
    void displayFieldsDoNotChangeRevision() {
        AssetSnapshotDto.SnapshotDetailResponse s = base();
        List<AssetSnapshotDto.DepositResponse> deposits = new ArrayList<>();
        for (AssetSnapshotDto.DepositResponse d : s.deposits()) {
            deposits.add(new AssetSnapshotDto.DepositResponse(d.id(), d.bankId(), "改名銀行", d.depositType(), "改名類型",
                    d.amount(), d.originalAmount(), d.currency(), d.annualInterestRate(), d.estimatedAnnualInterest(),
                    "新備註", d.updateMode(), d.processingDate()));
        }
        List<AssetSnapshotDto.FundResponse> funds = new ArrayList<>();
        for (AssetSnapshotDto.FundResponse f : s.funds()) {
            funds.add(new AssetSnapshotDto.FundResponse(f.id(), f.fundName(), f.fundCode(), f.bankId(), "改名銀行",
                    f.investmentAmount(), f.currentValue(), f.units(), f.estimatedDividend(), f.dividendRate(),
                    f.profit(), f.profitRate()));
        }
        List<AssetSnapshotDto.StockResponse> stocks = new ArrayList<>();
        for (AssetSnapshotDto.StockResponse st : s.stocks()) {
            stocks.add(new AssetSnapshotDto.StockResponse(st.id(), st.stockCode(), "改名股票", st.market(), st.brokerId(),
                    "改名券商", st.shares(), st.investmentCost(), st.investmentCostTwd(), st.currentValue(), st.profit(),
                    st.profitRate(), st.estimatedDividend(), st.dividendRate(), st.currency(), st.originalCurrencyValue(),
                    st.transactionType(), st.transactionDate(), st.transactionExchangeRate(), 99));
        }
        var renamed = new AssetSnapshotDto.SnapshotDetailResponse(s.id(), s.snapshotDate(), s.usdExchangeRate(),
                s.totalDeposit(), s.totalFundValue(), s.totalFundCost(), s.totalStockValue(), s.totalStockCost(),
                s.totalAssets(), s.estimatedAnnualDividend(), s.realizedGain(), "新快照備註",
                List.copyOf(deposits.reversed()), funds, stocks);
        assertThat(SrppSnapshotRevision.of(renamed)).isEqualTo(SrppSnapshotRevision.of(s));
    }

    @Test
    void assetContentChangesRevisionButScaleDoesNot() {
        AssetSnapshotDto.SnapshotDetailResponse s = base();
        var changed = new AssetSnapshotDto.SnapshotDetailResponse(s.id(), s.snapshotDate(), s.usdExchangeRate(),
                s.totalDeposit().add(BigDecimal.ONE), s.totalFundValue(), s.totalFundCost(), s.totalStockValue(),
                s.totalStockCost(), s.totalAssets(), s.estimatedAnnualDividend(), s.realizedGain(), s.notes(),
                s.deposits(), s.funds(), s.stocks());
        assertThat(SrppSnapshotRevision.of(changed)).isNotEqualTo(SrppSnapshotRevision.of(s));
        var rescaled = new AssetSnapshotDto.SnapshotDetailResponse(s.id(), s.snapshotDate(), s.usdExchangeRate(),
                s.totalDeposit().setScale(4), s.totalFundValue(), s.totalFundCost(), s.totalStockValue(),
                s.totalStockCost(), s.totalAssets(), s.estimatedAnnualDividend(), s.realizedGain(), s.notes(),
                s.deposits(), s.funds(), s.stocks());
        assertThat(SrppSnapshotRevision.of(rescaled)).isEqualTo(SrppSnapshotRevision.of(s));
        assertThat(SrppSnapshotRevision.of(s)).matches("^snapshot-1-[0-9a-f]{64}$");
    }
}
