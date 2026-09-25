package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.dto.AssetSnapshotDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Requirement 163／Task 452.6：快照 persisted 投影的 revision（{@code SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1}）。
 *
 * <p>投影 {@code SnapshotDetailResponse} 的資產內容欄位，排除非資產內容的顯示欄位：頂層與存款的 {@code notes}、
 * 存款 {@code bankDisplayName}／{@code depositDisplayName}、基金 {@code bankDisplayName}、股票 {@code stockName}／
 * {@code brokerDisplayName}／{@code displayOrder}（主檔改名、備註、調整順序不應讓 package 變 STALE）。
 * {@code FundResponse.fundName} 是持有列自身保存的資料，刻意保留。列依 id 數值升冪；BigDecimal 以
 * {@link SrppDecimal#format}；日期 ISO；null 保留。JCS→SHA-256，回傳 {@code snapshot-<id>-<64hex>}。
 * producer（t452）與讀取端 freshness（t453）共用本函式；改動投影必須同步改 manifest 版本標記。
 */
public final class SrppSnapshotRevision {
    private SrppSnapshotRevision() {}

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static String of(AssetSnapshotDto.SnapshotDetailResponse s) {
        return "snapshot-" + s.id() + "-" + SrppJcs.hash(projection(s));
    }

    static ObjectNode projection(AssetSnapshotDto.SnapshotDetailResponse s) {
        ObjectNode root = F.objectNode();
        num(root, "id", s.id());
        date(root, "snapshotDate", s.snapshotDate());
        dec(root, "usdExchangeRate", s.usdExchangeRate());
        dec(root, "totalDeposit", s.totalDeposit());
        dec(root, "totalFundValue", s.totalFundValue());
        dec(root, "totalFundCost", s.totalFundCost());
        dec(root, "totalStockValue", s.totalStockValue());
        dec(root, "totalStockCost", s.totalStockCost());
        dec(root, "totalAssets", s.totalAssets());
        dec(root, "estimatedAnnualDividend", s.estimatedAnnualDividend());
        dec(root, "realizedGain", s.realizedGain());
        root.set("deposits", rows(s.deposits(), AssetSnapshotDto.DepositResponse::id, d -> {
            ObjectNode o = F.objectNode();
            num(o, "id", d.id());
            num(o, "bankId", d.bankId());
            str(o, "depositType", d.depositType());
            dec(o, "amount", d.amount());
            dec(o, "originalAmount", d.originalAmount());
            str(o, "currency", d.currency());
            dec(o, "annualInterestRate", d.annualInterestRate());
            dec(o, "estimatedAnnualInterest", d.estimatedAnnualInterest());
            str(o, "updateMode", d.updateMode());
            date(o, "processingDate", d.processingDate());
            return o;
        }));
        root.set("funds", rows(s.funds(), AssetSnapshotDto.FundResponse::id, f -> {
            ObjectNode o = F.objectNode();
            num(o, "id", f.id());
            str(o, "fundName", f.fundName());
            str(o, "fundCode", f.fundCode());
            num(o, "bankId", f.bankId());
            dec(o, "investmentAmount", f.investmentAmount());
            dec(o, "currentValue", f.currentValue());
            dec(o, "units", f.units());
            dec(o, "estimatedDividend", f.estimatedDividend());
            dec(o, "dividendRate", f.dividendRate());
            dec(o, "profit", f.profit());
            dec(o, "profitRate", f.profitRate());
            return o;
        }));
        root.set("stocks", rows(s.stocks(), AssetSnapshotDto.StockResponse::id, st -> {
            ObjectNode o = F.objectNode();
            num(o, "id", st.id());
            str(o, "stockCode", st.stockCode());
            str(o, "market", st.market());
            num(o, "brokerId", st.brokerId());
            dec(o, "shares", st.shares());
            dec(o, "investmentCost", st.investmentCost());
            dec(o, "investmentCostTwd", st.investmentCostTwd());
            dec(o, "currentValue", st.currentValue());
            dec(o, "profit", st.profit());
            dec(o, "profitRate", st.profitRate());
            dec(o, "estimatedDividend", st.estimatedDividend());
            dec(o, "dividendRate", st.dividendRate());
            str(o, "currency", st.currency());
            dec(o, "originalCurrencyValue", st.originalCurrencyValue());
            str(o, "transactionType", st.transactionType());
            date(o, "transactionDate", st.transactionDate());
            dec(o, "transactionExchangeRate", st.transactionExchangeRate());
            return o;
        }));
        return root;
    }

    private static <T> ArrayNode rows(List<T> rows, Function<T, Long> id, Function<T, ObjectNode> mapper) {
        ArrayNode out = F.arrayNode();
        if (rows == null) return out;
        rows.stream()
                .sorted(Comparator.comparing(id, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(mapper)
                .forEach(out::add);
        return out;
    }

    private static void num(ObjectNode o, String key, Long value) {
        if (value == null) o.putNull(key);
        else o.put(key, value);
    }

    private static void dec(ObjectNode o, String key, BigDecimal value) {
        if (value == null) o.putNull(key);
        else o.put(key, SrppDecimal.format(value));
    }

    private static void str(ObjectNode o, String key, String value) {
        if (value == null) o.putNull(key);
        else o.put(key, value);
    }

    private static void date(ObjectNode o, String key, LocalDate value) {
        if (value == null) o.putNull(key);
        else o.put(key, value.toString());
    }
}
