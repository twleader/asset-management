package com.steven.assets.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 資產類別判定（Requirement 25）：把股票/ETF/基金歸入「現金／債券／股票」。
 *
 * 規則自動判定 + 可逐檔人工微調：個股 override（stock.asset_class）非空時一律優先，
 * 否則依以下規則推斷。override 為真正的事實來源（存 DB），規則只是合理預設。
 */
@Component
public class AssetClassifier {

    public static final String CASH = "CASH";
    public static final String BOND = "BOND";
    public static final String STOCK = "STOCK";

    // 股票風格（Requirement 26）
    public static final String GROWTH = "GROWTH";
    public static final String INCOME = "INCOME";

    // 債券期別（Requirement 27）
    public static final String SHORT = "SHORT";
    public static final String MID = "MID";
    public static final String LONG = "LONG";

    /** 殖利率門檻 fallback（當 stock_style INCOME 列未設 dividend_threshold 時）= 4% */
    private static final BigDecimal DEFAULT_DIVIDEND_THRESHOLD = new BigDecimal("0.04");

    /**
     * 高股息／收益型 ETF 預設清單（規則推斷用；命中清單一律收益型，不受殖利率波動影響）。
     * 漏網者可在「資產類別歸類」頁逐檔 override。
     */
    private static final Set<String> HIGH_DIVIDEND_ETFS = Set.of(
            // 台股高股息 ETF
            "0056", "00878", "00919", "00713", "00882", "00929", "00940", "00936",
            "00915", "00918", "00927", "00939", "00943", "00946", "00701", "00730", "00731",
            "00900", "00712", "00908", "00932",
            // 美股高股息 / 收益型 ETF（不含特別股 ETF PFF/PFFD：屬類債券混合，
            // 不在此自動歸收益型股票，必要時由使用者 override 為債券）
            "SCHD", "VYM", "HDV", "DVY", "SPYD", "SDY", "DGRO", "VIG", "NOBL",
            "JEPI", "JEPQ", "DIVO", "QYLD", "RYLD", "XYLD"
    );

    /**
     * 美股／英股債券 ETF 預設清單（規則推斷用；個別標的可由 override 覆蓋）。
     * 美股代號無命名規則可循，只能維護清單；漏網者使用者可在「資產類別歸類」頁手動指定。
     */
    private static final Set<String> US_BOND_ETFS = Set.of(
            // 公債（短/中/長天期）
            "TLT", "TLH", "IEF", "IEI", "SHY", "SHV", "GOVT", "GOVZ", "BIL", "BILS", "SGOV",
            "VGSH", "VGIT", "VGLT", "SPTL", "SPTS", "SPTI",
            // 綜合債
            "BND", "BNDX", "BNDW", "AGG", "BSV", "BIV", "BLV", "SPAB",
            // 投資級公司債
            "LQD", "VCIT", "VCSH", "VCLT", "IGSB", "IGIB", "USIG",
            // 抗通膨（TIPS）
            "TIP", "VTIP", "SCHP", "STIP", "TIPX",
            // 市政債
            "MUB", "VTEB", "TFI", "SUB",
            // 非投資級／新興市場債
            "HYG", "JNK", "SHYG", "USHY", "ANGL", "EMB", "VWOB", "PCY",
            // 房貸抵押債
            "MBB", "VMBS"
    );

    /** 台股債券 ETF：代號 00 開頭且結尾為 B（櫃買債券 ETF 命名慣例，如 00679B、00687B） */
    private boolean isTwBondEtf(String code) {
        if (code == null) return false;
        String c = code.trim().toUpperCase();
        return c.startsWith("00") && c.endsWith("B");
    }

    /** 依規則推斷股票/ETF 的資產類別（不含 override） */
    public String classifyStockByRule(String code, String market) {
        if (code == null) return STOCK;
        String c = code.trim().toUpperCase();
        if ("台股".equals(market)) {
            return isTwBondEtf(c) ? BOND : STOCK;
        }
        // 美股 / 英股 / 其餘市場：比對債券 ETF 清單
        return US_BOND_ETFS.contains(c) ? BOND : STOCK;
    }

    /** 股票/ETF 有效資產類別：override 優先，否則規則。 */
    public String classifyStock(String code, String market, String override) {
        if (override != null && !override.isBlank()) return override.trim().toUpperCase();
        return classifyStockByRule(code, market);
    }

    /**
     * 債券期別判定（Requirement 27）：override 優先，否則依名稱／代號年期推斷。
     * 年期帶在標的名稱（如「元大美債20年」「富邦美債7-10」「iShares 0-3 Month Treasury」）。
     * 短 ≤3年、中 3–10年、長 >10年；無法判斷者預設中期，使用者可 override。
     * 僅應對「有效 asset_class = BOND」者呼叫。
     */
    public String classifyBondTerm(String code, String market, String name, String override) {
        if (override != null && !override.isBlank()) return override.trim().toUpperCase();
        String n = name == null ? "" : name.toLowerCase();
        // 短期：1-3 / 0-3 / 0-1 / 1年 / 短 / month（貨幣型、超短天期）
        if (n.contains("1-3") || n.contains("0-3") || n.contains("0-1") || n.contains("1-5")
                || n.contains("短") || n.contains("month") || n.contains("貨幣")) return SHORT;
        // 長期：20 / 25 / 30年 / 長 / 10年以上 / 10-20 / 20+
        if (n.contains("20") || n.contains("25") || n.contains("30年") || n.contains("長")
                || n.contains("10年以上") || n.contains("10-20") || n.contains("20+")) return LONG;
        // 中期：7-10 / 5-10 / 3-7 / 中 / 3-10
        if (n.contains("7-10") || n.contains("5-10") || n.contains("3-7") || n.contains("3-10")
                || n.contains("中")) return MID;
        return MID;  // 無年期資訊（如一般公司債）預設中期，可 override
    }

    /** 基金有效資產類別：override 優先；否則名稱含「債」或 bond → 債券，其餘 → 股票。 */
    public String classifyFund(String fundName, String override) {
        if (override != null && !override.isBlank()) return override.trim().toUpperCase();
        if (fundName != null) {
            String lower = fundName.toLowerCase();
            if (fundName.contains("債") || lower.contains("bond")) return BOND;
        }
        return STOCK;
    }

    /**
     * 股票風格判定（Requirement 26），優先序由高到低：
     *   1. override 非空 → 直接採用
     *   2. 代號 ∈ 高股息 ETF 清單 → 收益型（不受殖利率波動影響）
     *   3. dividendRate ≥ 門檻 → 收益型
     *   4. 其餘（含 dividendRate null/0）→ 成長型
     * 僅應對「有效 asset_class = STOCK」者呼叫；債券/現金不分風格。
     *
     * @param dividendRate 該快照該持股的殖利率（小數，如 0.04 = 4%），可為 null
     * @param threshold    收益型門檻（來自 stock_style INCOME 列），null 時用預設 4%
     */
    public String classifyStockStyle(String code, String market, String styleOverride,
                                     BigDecimal dividendRate, BigDecimal threshold) {
        if (styleOverride != null && !styleOverride.isBlank()) return styleOverride.trim().toUpperCase();
        if (code != null && HIGH_DIVIDEND_ETFS.contains(code.trim().toUpperCase())) return INCOME;
        BigDecimal cutoff = (threshold != null) ? threshold : DEFAULT_DIVIDEND_THRESHOLD;
        if (dividendRate != null && dividendRate.compareTo(cutoff) >= 0) return INCOME;
        return GROWTH;
    }
}
