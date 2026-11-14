package com.steven.assets.bff.portfolioadvice;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.steven.assets.bff.portfolioadvice.SrppShortTermDto.ListEnvelope;
import com.steven.assets.bff.portfolioadvice.SrppShortTermDto.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Page layout only: preserve source observations without deriving a trading signal. */
public final class SrppShortTermPresentation {
    private SrppShortTermPresentation() {}

    public record ObservationBucket(String key,String label,String emptyText,List<Row> rows) {
        public ObservationBucket { rows=List.copyOf(rows); }
    }
    public record PageEnvelope(@JsonUnwrapped ListEnvelope source,List<ObservationBucket> observationBuckets) {
        public PageEnvelope { Objects.requireNonNull(source);observationBuckets=List.copyOf(observationBuckets); }
    }

    public static PageEnvelope list(ListEnvelope source) {
        Objects.requireNonNull(source);
        List<Row> buy=new ArrayList<>(),sell=new ArrayList<>(),wait=new ArrayList<>();
        for(Row row:source.rows()) {
            if("BUY_WATCH".equals(row.observationType()))buy.add(row);
            else if("SELL_WATCH".equals(row.observationType()))sell.add(row);
            else wait.add(row); // Missing/unknown source classification remains visible without changing the row.
        }
        return new PageEnvelope(source,List.of(
                new ObservationBucket("BUY_WATCH","買進觀察（僅來源短線方向）","目前沒有來源短線買進觀察；不由分數或中長線理由推導。",buy),
                new ObservationBucket("SELL_WATCH","減碼／退出觀察（僅來源短線方向）","目前沒有來源短線減碼／退出觀察；不從持股狀態自行新增。",sell),
                new ObservationBucket("WAIT","等待資料或訊號","目前沒有等待資料的列。",wait)));
    }
}
