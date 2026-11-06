package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class FubonSettlementLedgerNoteReconcilerTest {
    private final FubonSettlementLedgerNoteReconciler reconciler = new FubonSettlementLedgerNoteReconciler();

    @Test
    void realDayTradeFactsReconcileManualAndSyncedRowsAndUseOfficialNet() {
        var rows = realLedger();
        var day = realDay();
        assertThat(reconciler.verify(1L, day, rows)).contains(
                "富邦證券已記錄成交：買 0050、00881；賣 00881；淨付 90704");
        var batch = read(settlementJson(realJson()), FubonDtos.SettlementBatch.class);
        var projection = FubonAccountingContract.settlementProjection(batch);
        assertThat(projection.payableAmount()).isEqualByComparingTo("-90704");
        assertThat(projection.receivableAmount()).isZero();
    }

    @Test
    void wrongOwnerDateChannelSourceOrMissingFeeNeverProvesANote() {
        for (String mutation : List.of("owner", "date", "channel", "source", "fee", "tax", "price", "amount")) {
            var rows = realLedger();
            var row = rows.getFirst();
            switch (mutation) {
                case "owner" -> row.setOwnerUserId(2L);
                case "date" -> row.setTradeDate(DATE.minusDays(1));
                case "channel" -> row.setChannel("其他券商");
                case "source" -> row.setSource("UNKNOWN");
                case "fee" -> row.setFee(null);
                case "tax" -> row.setTransactionTax(null);
                case "price" -> row.setPrice(new BigDecimal("40.88"));
                case "amount" -> row.setAmount(new BigDecimal("40880"));
            }
            assertThat(reconciler.verify(1L, realDay(), rows)).as(mutation).isEmpty();
        }
        assertThat(reconciler.verify(1L, realDay(), realLedger().subList(0, 2))).isEmpty();
    }

    @Test
    void unknownSourceDirectionTaxesNeedCompleteLedgerTaxesAndSourceTotalChecks() {
        String json = realJson().replace("\"buyTax\":\"0\"", "\"buyTax\":null")
                .replace("\"sellTax\":\"40\"", "\"sellTax\":null");
        var day = read(settlementJson(json), FubonDtos.SettlementBatch.class).details().getFirst();
        assertThat(reconciler.verify(1L, day, realLedger())).isPresent();
        var invalid = read(settlementJson(json.replace("\"totalTax\":\"40\"", "\"totalTax\":\"41\"")),
                FubonDtos.SettlementBatch.class).details().getFirst();
        assertThat(reconciler.verify(1L, invalid, realLedger())).isEmpty();
    }

    private static FubonDtos.SettlementDay realDay() {
        return read(settlementJson(realJson()), FubonDtos.SettlementBatch.class).details().getFirst();
    }

    private static String realJson() {
        return """
                {"status":"AVAILABLE","sourceQueryDate":"2026-08-28","settlementDate":"2026-09-01","currency":"TWD",
                "buyValue":"131220","buyFee":"186","buySettlement":"-131406","buyTax":"0",
                "sellValue":"40800","sellFee":"58","sellSettlement":"40702","sellTax":"40",
                "totalBsValue":"172020","totalFee":"244","totalTax":"40","totalSettlementAmount":"-90704"}
                """;
    }

    private static List<AssetTransaction> realLedger() {
        List<AssetTransaction> rows = new ArrayList<>();
        rows.add(trade("賣", "00881", "40.80", "40800", "58", "40", "MANUAL"));
        rows.add(trade("買", "00881", "38.37", "38370", "54", "0", "MANUAL"));
        rows.add(trade("買", "0050", "92.85", "92850", "132", "0", "FUBON_SYNC"));
        return rows;
    }

    private static AssetTransaction trade(String direction, String code, String price, String amount,
            String fee, String tax, String source) {
        return AssetTransaction.builder().ownerUserId(1L).tradeDate(DATE).channel("富邦證券")
                .market("台股").currency("TWD").assetType("股票").assetCode(code).assetName(code)
                .transactionType(direction).source(source).shares(new BigDecimal("1000"))
                .price(new BigDecimal(price)).amount(new BigDecimal(amount)).fee(new BigDecimal(fee))
                .transactionTax(new BigDecimal(tax)).build();
    }
}
