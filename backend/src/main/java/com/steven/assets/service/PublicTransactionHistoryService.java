package com.steven.assets.service;

import com.steven.assets.dto.PublicTransactionHistoryDto;
import com.steven.assets.dto.PublicTransactionHistoryDto.PublicTransactionRecord;
import com.steven.assets.dto.PublicTransactionHistoryDto.TransactionHistoryMode;
import com.steven.assets.dto.PublicTransactionHistoryDto.TransactionPeriodSummary;
import com.steven.assets.dto.PublicTransactionHistoryDto.TransactionYearSummary;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.repository.AssetTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Configured-admin selection is supplied by BFF headers; this service only reads the tenant-filtered ledger. */
@Service
@RequiredArgsConstructor
public class PublicTransactionHistoryService {

    private final AssetTransactionRepository transactions;

    @Transactional(readOnly = true)
    public PublicTransactionHistoryDto.PublicTransactionHistoryResponse current(
            List<String> years, List<String> starts, List<String> ends) {
        PublicTransactionHistoryFilter.Filter filter = PublicTransactionHistoryFilter.parse(years, starts, ends);
        List<PublicTransactionRecord> all = transactions.findAllByOrderByTradeDateDescIdDesc().stream()
                .map(this::toRecord)
                .toList();
        List<PublicTransactionRecord> selected = switch (filter.selection().mode()) {
            case ALL -> all;
            case YEAR, DATE_RANGE -> all.stream()
                    .filter(record -> isWithin(record.tradeDate(), filter.start(), filter.end()))
                    .toList();
        };
        return new PublicTransactionHistoryDto.PublicTransactionHistoryResponse(
                filter.selection(), summarize(all), summarize(selected), yearSummaries(selected), selected);
    }

    /** The configured owner's ledger is read exactly once; selection is a projection of that stable ordered snapshot. */
    private static boolean isWithin(LocalDate tradeDate, LocalDate start, LocalDate end) {
        return tradeDate != null && !tradeDate.isBefore(start) && !tradeDate.isAfter(end);
    }

    private List<TransactionYearSummary> yearSummaries(List<PublicTransactionRecord> records) {
        Map<Integer, List<PublicTransactionRecord>> byYear = records.stream().collect(Collectors.groupingBy(
                PublicTransactionRecord::year, () -> new TreeMap<>(Comparator.reverseOrder()), Collectors.toList()));
        List<TransactionYearSummary> summaries = new ArrayList<>();
        for (Map.Entry<Integer, List<PublicTransactionRecord>> entry : byYear.entrySet()) {
            TransactionPeriodSummary summary = summarize(entry.getValue());
            summaries.add(new TransactionYearSummary(entry.getKey(), summary.buyCount(), summary.sellCount(),
                    summary.totalBuyAmountTwd(), summary.totalSellAmountTwd()));
        }
        return List.copyOf(summaries);
    }

    private TransactionPeriodSummary summarize(List<PublicTransactionRecord> records) {
        int buyCount = 0;
        int sellCount = 0;
        BigDecimal totalBuy = BigDecimal.ZERO;
        BigDecimal totalSell = BigDecimal.ZERO;
        for (PublicTransactionRecord record : records) {
            BigDecimal amountTwd = record.amountTwd() == null ? BigDecimal.ZERO : record.amountTwd();
            if ("賣".equals(record.transactionType())) {
                sellCount++;
                totalSell = totalSell.add(amountTwd);
            } else {
                buyCount++;
                totalBuy = totalBuy.add(amountTwd);
            }
        }
        return new TransactionPeriodSummary(buyCount, sellCount, totalBuy, totalSell);
    }

    /** Same amountTwd rule as the private ledger response; fee/tax never enter this calculation. */
    private PublicTransactionRecord toRecord(AssetTransaction transaction) {
        BigDecimal amountTwd = transaction.getAmount();
        if ("USD".equals(transaction.getCurrency()) && transaction.getExchangeRate() != null
                && transaction.getAmount() != null) {
            amountTwd = transaction.getAmount().multiply(transaction.getExchangeRate());
        }
        return new PublicTransactionRecord(
                transaction.getId(), transaction.getTransactionType(), transaction.getAssetType(),
                transaction.getAssetName(), transaction.getAssetCode(), transaction.getMarket(), transaction.getCurrency(),
                transaction.getChannel(), transaction.getTradeDate(), transaction.getShares(), transaction.getPrice(),
                transaction.getAmount(), transaction.getFee(), transaction.getTransactionTax(), transaction.getExchangeRate(),
                transaction.getNotes(), amountTwd, transaction.getYear());
    }
}
