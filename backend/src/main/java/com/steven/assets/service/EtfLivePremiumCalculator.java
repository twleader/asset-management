package com.steven.assets.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ETF 即時折溢價（%）的<b>單一共用實作</b>（Task 320.1）。
 *
 * <p>原本這段分流只存在於 {@code ExcelExportService.premiumDiscountPct}（Task 214／259），
 * 交易雷達要顯示同義欄位時若在 {@code TradingRadarService} 另寫一份，兩處會各自演化成
 * 「一個頁面對、另一個頁面錯 0.07 個百分點」的靜默分岔（CLAUDE.md「同義欄位、同一 business service API」）。
 * 故抽為純靜態、無 Spring 依賴、可脫離 context 單元測試的共用實作，由資產總覽 Excel 與交易雷達共同呼叫。
 *
 * <p><b>判斷順序刻意照抄原實作，不得改寫成「市場二分」</b>（Task 320.2 的第 0 條）：
 * 先看 {@code nav == null}，再看來源是否已提供權威值（<b>不看 market</b>），台股缺漏回 null，
 * 其餘才以本列即時價反推。今天美股 payload 恆無 {@code premiumDiscountPct}，故與
 * {@code if (市場 == 台股) 直取 else 反推} 在數值上等價；但寫成後者就偏離了現行語意，
 * 一旦 Yahoo 端未來補上該欄就會靜默改變輸出。
 *
 * <p>三條紅線（違反會產生「平盤日正常、大跌日離譜」的靜默錯誤）：
 * <ol>
 *   <li><b>台股直接取權威值、一律不重算</b>：取證交所 {@code all_etf.txt} 已算好的折溢價欄。
 *       自行以 (市價 − 淨值)/淨值 重算的誤差達 0.07 個百分點（淨值欄在股票型 ETF 四捨五入至小數 2 位）；
 *       改用「前一交易日淨值」欄更糟——該欄對全部檔位皆為 T-1，實測台股重挫日會把 0050 的真實
 *       +1.2% 溢價算成 −5.8% 折價。台股該欄留白時回 {@code null}，<b>不反推</b>（Task 259 鐵則）。</li>
 *   <li><b>美股以該列自己的現價反推</b>：Yahoo 不提供折溢價欄。用同回應的 {@code previousClose}
 *       會把 VOO 真實 +0.003% 溢價放大成 +1.02%，故一律用呼叫端該列畫面上顯示的現價，保證列內自洽。</li>
 *   <li>現價或淨值任一為 {@code null}、或淨值為 0 時回 {@code null}；個股（Redis 查無
 *       {@code price:etfnav:*} key）恆為 {@code null}，不補 0、不補「N/A」、不做 ETF 白名單。</li>
 * </ol>
 */
public final class EtfLivePremiumCalculator {

    private EtfLivePremiumCalculator() {}

    /** 台股權威折溢價的市場代碼；與 {@code PriceQueryService.EtfNav#market()} 寫入值一致。 */
    private static final String TW_MARKET = "台股";

    /**
     * 依上述順序計算折溢價（%）；1.2 表示溢價 1.2%、負值為折價。無法判定一律回 {@code null}（留白）。
     *
     * @param nav       ETF 淨值／折溢價 payload；Redis 查無（個股）時傳 {@code null}
     * @param livePrice <b>呼叫端該列已顯示的即時價</b>，不得為此另取一次價（否則現價與折溢價會落在不同 tick）
     */
    public static BigDecimal premiumDiscountPct(PriceQueryService.EtfNav nav, BigDecimal livePrice) {
        if (nav == null) return null;
        if (nav.premiumDiscountPct() != null) return nav.premiumDiscountPct();
        if (TW_MARKET.equals(nav.market())) return null; // Task 259：台股折溢價缺漏不反推
        if (livePrice == null || nav.nav() == null || nav.nav().compareTo(BigDecimal.ZERO) == 0) return null;
        return livePrice.subtract(nav.nav())
                .multiply(BigDecimal.valueOf(100))
                .divide(nav.nav(), 2, RoundingMode.HALF_UP);
    }
}
