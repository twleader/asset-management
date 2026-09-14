package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FubonTransitNoteFormatterTest {
    private final FubonTransitNoteFormatter formatter = new FubonTransitNoteFormatter();

    @Test
    void payableUsesBuyDirectionStableSortAndMergesSameInstrumentLots() {
        String note = formatter.format("買股待付款", List.of(
                trade(3L, "買", "2330", "台積電", "1000"),
                trade(2L, "買", "00719B", "元大美債1-3", "500"),
                trade(1L, "買", "00719B", "元大美債1-3", "500"),
                trade(4L, "賣", "2885", "元大金", "1000")));

        assertThat(note).isEqualTo("富邦證券當日已同步成交：買入 元大美債1-3（00719B）1 張；買入 台積電（2330）1 張");
    }

    @Test
    void receivableUsesSellDirectionAndKeepsOddLotExact() {
        String note = formatter.format("賣股待收款", List.of(
                trade(1L, "賣", "2885", "元大金", "1500"),
                trade(2L, "買", "00719B", "元大美債1-3", "1000")));

        assertThat(note).isEqualTo("富邦證券當日已同步成交：賣出 元大金（2885）1500 股（1.5 張）");
    }

    @Test
    void emptyOrWrongDirectionUsesGenericSettlementNote() {
        assertThat(formatter.format("買股待付款", List.of())).isEqualTo("富邦證券交割款；當日無已同步成交明細");
        assertThat(formatter.format("賣股待收款", List.of(trade(1L, "買", "00719B", "元大美債1-3", "1000"))))
                .isEqualTo("富邦證券交割款；當日無已同步成交明細");
    }

    @Test
    void overlongCompleteDetailUsesCountSummaryWithoutTruncatingAList() {
        List<AssetTransaction> many = IntStream.range(0, 30)
                .mapToObj(index -> trade((long) index, "買", String.format("%05d", index),
                        "測試標的名稱很長" + index, "1000"))
                .toList();

        String note = formatter.format("買股待付款", many);

        assertThat(note).isEqualTo("富邦證券當日已同步成交：買入共 30 個標的（明細過長）");
        assertThat(note.length()).isLessThanOrEqualTo(200);
    }

    private static AssetTransaction trade(Long id, String direction, String code, String name, String shares) {
        return AssetTransaction.builder().id(id).transactionType(direction).assetCode(code).assetName(name)
                .shares(new BigDecimal(shares)).build();
    }
}
