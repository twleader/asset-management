package com.steven.assets.service;

import com.steven.assets.dto.AssetTransactionDto;
import com.steven.assets.dto.InstitutionDto;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.FundClassOverride;
import com.steven.assets.model.Stock;
import com.steven.assets.repository.AssetClassRepository;
import com.steven.assets.repository.FundClassOverrideRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Authenticated transaction projection over the existing global classification authority. */
@Service
@RequiredArgsConstructor
public class TransactionAssetClassService {
    private final StockRepository stocks;
    private final FundClassOverrideRepository funds;
    private final AssetClassRepository categories;
    private final AssetClassifier classifier;
    private final InstitutionService institutions;
    private final CurrentUserContext currentUser;

    public record Classification(String assetClassOverride, String effectiveAssetClass, boolean assetClassAssignable) {}
    public record Context(Map<String, Stock> stocks, Map<String, FundClassOverride> funds) {}

    public Context context(List<AssetTransaction> records) {
        var codes = records.stream().filter(t -> "股票".equals(t.getAssetType()))
                .map(AssetTransaction::getAssetCode).filter(c -> c != null && !c.isBlank()).distinct().toList();
        var names = records.stream().filter(t -> "基金".equals(t.getAssetType()))
                .map(AssetTransaction::getAssetName).filter(n -> n != null && !n.isBlank()).distinct().toList();
        Map<String, Stock> stockMap = new HashMap<>();
        if (!codes.isEmpty()) stocks.findAllByCodeIn(codes).forEach(s -> stockMap.put(key(s.getMarket(), s.getCode()), s));
        Map<String, FundClassOverride> fundMap = new HashMap<>();
        if (!names.isEmpty()) funds.findAllById(names).forEach(f -> fundMap.put(f.getFundName(), f));
        return new Context(stockMap, fundMap);
    }

    @Transactional(readOnly = true)
    public Classification lookup(String assetType, String code, String market, String name) {
        var tx = AssetTransaction.builder().assetType(assetType).assetCode(code).market(market).assetName(name).build();
        return resolve(tx, context(List.of(tx)));
    }

    public Classification resolve(AssetTransaction tx, Context context) {
        if ("基金".equals(tx.getAssetType())) {
            var fund = context.funds().get(tx.getAssetName());
            String override = fund == null ? null : fund.getAssetClass();
            return new Classification(override, classifier.classifyFund(tx.getAssetName(), override), validFund(tx.getAssetName()));
        }
        if (!"股票".equals(tx.getAssetType())) return new Classification(null, null, false);
        var stock = context.stocks().get(key(tx.getMarket(), tx.getAssetCode()));
        String override = stock == null ? null : stock.getAssetClass();
        return new Classification(override, classifier.classifyStock(tx.getAssetCode(), tx.getMarket(), override),
                stock != null && !("台股".equals(tx.getMarket()) && "0000".equals(tx.getAssetCode())));
    }

    /** Joins the ledger's transaction; callers must establish ownership before reaching this method. */
    @Transactional
    public void apply(AssetTransaction tx, AssetTransactionDto.AssetClassChange change) {
        if (change == null) return;
        if (!currentUser.isAdmin()) throw new AdminRequiredException("資產類別為共用設定，僅限管理者修改");
        String override = change.assetClass() == null || change.assetClass().isBlank() ? null : change.assetClass();
        if (override != null && ("CASH".equals(override) || categories.findByCode(override).isEmpty())) {
            throw new IllegalArgumentException("資產類別不存在或不可指定");
        }
        if (!resolve(tx, context(List.of(tx))).assetClassAssignable()) {
            throw new IllegalArgumentException("此標的尚無可指定的分類資料，請先建立主檔或使用自動分類");
        }
        boolean fund = "基金".equals(tx.getAssetType());
        institutions.setSecurityAssetClass(new InstitutionDto.SetSecurityAssetClassRequest(
                fund ? tx.getAssetName() : tx.getAssetCode(), fund ? "基金" : tx.getMarket(), override));
    }

    private static boolean validFund(String name) {
        return name != null && !name.isBlank() && name.length() <= 150;
    }
    private static String key(String market, String code) { return market + "|" + code; }
}
