package com.steven.assets.service;

import com.steven.assets.model.*;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.RealizedGainRepository;
import com.steven.assets.repository.StockRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 將快照與已實現損益匯出成 .xlsx
 * - 每個快照產生一張 sheet（名稱 YYYYMMDD），分區顯示銀行存款/基金/股票
 * - 已實現損益單獨一張 sheet
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelExportService {

    private final AssetSnapshotRepository snapshotRepo;
    private final RealizedGainRepository gainRepo;
    // 交易紀錄流水帳匯出（Requirement 49 / Task 237）：三入口（下載／run-now／排程）共用同一活頁簿
    private final com.steven.assets.repository.AssetTransactionRepository assetTxRepo;
    private final StockRepository stockMasterRepo;
    private final StockPriceService stockPriceService;
    // 月/季/年線與 KD 的共用權威計算（與觀察清單／警示同一來源）；供「股票（即時）」分頁增列技術指標欄（Task 200）
    private final TechnicalIndicatorService technicalIndicatorService;
    // 油價金價匯出（Task 202）：全域公開行情，與頁面曲線同一張表，確保匯出值與圖表一致
    private final com.steven.assets.repository.CommodityPriceHistoryRepository commodityHistRepo;
    // 台幣兌美元匯率匯出（Task 204）：同為全域公開資料，與頁面曲線同一張表
    private final com.steven.assets.repository.ExchangeRateHistoryRepository rateHistRepo;
    // 每檔持股「過去一年股價」分頁（Task 206）：收盤價權威來源，與 TechnicalIndicatorService 的 MA/KD 同源
    private final com.steven.assets.repository.StockPriceHistoryRepository priceHistRepo;
    // ETF 淨值／折溢價（Task 214）：讀 Redis price:etfnav:{market}:{code}（由 ext 排程寫入），business 不直連外部行情
    private final PriceQueryService priceQueryService;
    // 大盤指數日線匯出（Task 216）：與「股市大盤查詢」頁曲線同一張表，確保匯出值與圖表一致
    private final com.steven.assets.repository.TwseIndexDailyHistoryRepository twseIndexHistRepo;
    private final com.steven.assets.repository.UsIndexDailyHistoryRepository usIndexHistRepo;

    @PersistenceContext
    private EntityManager entityManager;

    private static final DateTimeFormatter SHEET_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");

    /**
     * 完整匯出：當前彙總 + 所有快照 + 已實現損益。
     * HTTP 情境下由 {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped（只含當前使用者資產）。
     */
    @Transactional(readOnly = true)
    public byte[] exportFull() throws IOException {
        return buildWorkbook();
    }

    /**
     * 背景排程用：指定 owner 的完整匯出（Requirement 34 / Task 171）。
     * 背景執行緒無 request context，{@code TenantFilterAspect} 不啟用，故在本 session 手動啟用
     * {@code ownerFilter} 縮到該 owner，確保只匯出該使用者自己的資產。
     */
    @Transactional(readOnly = true)
    public byte[] exportFullForOwner(Long ownerId) throws IOException {
        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", ownerId);
        return buildWorkbook();
    }

    /**
     * 排程／run-now 用：匯出「當前即時資產」（Requirement 34 增修）。
     * 內容＝最新一筆快照的持股／存款／基金，但股票以 Redis 即時股價重估（與 Dashboard 首頁「當前資產」
     * 同一權威來源 {@link StockPriceService#getLiveAssets()}；存款／基金沿用最新快照凍結值）。
     * HTTP 情境由 {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped。
     */
    @Transactional(readOnly = true)
    public byte[] exportLiveAssets() throws IOException {
        return buildLiveWorkbook();
    }

    /**
     * 背景排程用：指定 owner 的「當前即時資產」匯出。背景執行緒無 request context，
     * {@code TenantFilterAspect} 不啟用，故在本 session 手動 {@code enableFilter} 縮到該 owner，
     * 讓 {@code getLiveAssets()} 與最新快照查詢都只含該使用者資產。
     */
    @Transactional(readOnly = true)
    public byte[] exportLiveAssetsForOwner(Long ownerId) throws IOException {
        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", ownerId);
        return buildLiveWorkbook();
    }

    /**
     * 當前即時資產活頁簿：第一張「當前即時資產」總表（股票即時價，存款／基金讀最新快照），
     * 第二張起每檔持股一張「過去一年股價」分頁（分頁名＝股票代號，Task 206）。
     */
    private byte[] buildLiveWorkbook() throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            // 即時股票估值單一來源：與 Dashboard「當前資產」同一 getLiveAssets()（同一 session/交易，filter 生效）。
            StockPriceService.LiveAssetsResponse live = stockPriceService.getLiveAssets();
            AssetSnapshot latest = snapshotRepo.findLatest().orElse(null); // deposits/funds 明細於同交易 lazy load
            writeLiveAssetsSheet(wb, st, live, latest);
            writeStockPriceHistorySheets(wb, st, latest); // 持股清單與總表同一 latest，兩者必然一致
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 共用活頁簿建構：第一張「當前彙總」總表 + 每快照一張 sheet + 已實現損益。 */
    private byte[] buildWorkbook() throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            List<AssetSnapshot> snapshots = snapshotRepo.findAllByOrderBySnapshotDateAsc();
            writeCurrentSummarySheet(wb, st, snapshots);
            for (AssetSnapshot s : snapshots) {
                writeSnapshotSheet(wb, st, s);
            }
            writeRealizedGainsSheet(wb, st);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 只匯出當前彙總（讀最新一筆快照）。snapshots 需已依 snapshotDate 升冪。 */
    private void writeCurrentSummarySheet(Workbook wb, Styles st, List<AssetSnapshot> snapshots) {
        Sheet sheet = wb.createSheet("當前彙總");
        int r = 0;

        Row title = sheet.createRow(r++);
        cell(title, 0, "當前全資產彙總", st.section);

        Row exp = sheet.createRow(r++);
        cell(exp, 0, "匯出時間", st.head);
        cell(exp, 1, LocalDateTime.now(TW_ZONE).format(TS_FMT), null);

        if (snapshots.isEmpty()) {
            Row none = sheet.createRow(r++);
            cell(none, 0, "尚無資產快照", null);
            for (int i = 0; i < 4; i++) sheet.autoSizeColumn(i);
            return;
        }

        AssetSnapshot s = snapshots.get(snapshots.size() - 1); // 最新一筆（已依日期升冪）

        Row d = sheet.createRow(r++);
        cell(d, 0, "最新快照日期", st.head);
        cell(d, 1, ISO.format(s.getSnapshotDate()), null);
        cell(d, 2, "美元匯率", st.head);
        cell(d, 3, s.getUsdExchangeRate(), st.num4);

        r++; // 空行

        Row secHead = sheet.createRow(r++);
        cell(secHead, 0, "項目", st.head);
        cell(secHead, 1, "金額 (台幣)", st.head);

        r = summaryRow(sheet, st, r, "資產總計", s.getTotalAssets());
        r = summaryRow(sheet, st, r, "存款總計", s.getTotalDeposit());
        r = summaryRow(sheet, st, r, "股票現值", s.getTotalStockValue());
        r = summaryRow(sheet, st, r, "股票成本", s.getTotalStockCost());
        r = summaryRow(sheet, st, r, "股票未實現損益", diff(s.getTotalStockValue(), s.getTotalStockCost()));
        r = summaryRow(sheet, st, r, "基金現值", s.getTotalFundValue());
        r = summaryRow(sheet, st, r, "基金成本", s.getTotalFundCost());
        r = summaryRow(sheet, st, r, "基金未實現損益", diff(s.getTotalFundValue(), s.getTotalFundCost()));
        r = summaryRow(sheet, st, r, "預估年配息", s.getEstimatedAnnualDividend());
        r = summaryRow(sheet, st, r, "當年度已實現損益", s.getRealizedGain());

        for (int i = 0; i < 4; i++) sheet.autoSizeColumn(i);
    }

    private int summaryRow(Sheet sheet, Styles st, int r, String label, BigDecimal value) {
        Row row = sheet.createRow(r);
        cell(row, 0, label, st.head);
        cell(row, 1, value, st.money);
        return r + 1;
    }

    private static BigDecimal diff(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return null;
        BigDecimal x = a != null ? a : BigDecimal.ZERO;
        BigDecimal y = b != null ? b : BigDecimal.ZERO;
        return x.subtract(y);
    }

    /** 股票投資成本一律台幣：USD 計價股依交易日匯率（無則 fallback 匯率）換算；非 USD 直接回原值。 */
    private static BigDecimal stockCostTwd(StockHolding sk, BigDecimal fallbackRate) {
        if (!"USD".equals(sk.getCurrency()) || sk.getInvestmentCost() == null) {
            return sk.getInvestmentCost();
        }
        BigDecimal rate = sk.getTransactionExchangeRate() != null ? sk.getTransactionExchangeRate()
                : (fallbackRate != null ? fallbackRate : BigDecimal.ONE);
        return sk.getInvestmentCost().multiply(rate).setScale(0, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 單筆持股即時現值（台幣）：即時價 × 股數，美股／英股再 × 匯率；與 {@link StockPriceService#getLiveAssets()}
     * 每檔算法一致（逐列 sum 會等於 live.liveStockValue()）。無即時價則 fallback 快照凍結現值。
     */
    private static BigDecimal liveStockValueTwd(StockHolding sk, BigDecimal price, BigDecimal rate) {
        if (price == null || sk.getShares() == null) {
            return sk.getCurrentValue();
        }
        BigDecimal v = sk.getShares().multiply(price);
        if ("美股".equals(sk.getMarket()) || "英股".equals(sk.getMarket())) {
            v = v.multiply(rate != null ? rate : BigDecimal.ONE);
        }
        return v.setScale(0, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 只匯出已實現損益（含全部年度）。
     * HTTP 情境下由 {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped。
     * 手動下載、排程 run-now 皆走此方法，與排程產出同一份活頁簿。
     */
    @Transactional(readOnly = true)
    public byte[] exportRealizedGains() throws IOException {
        return buildRealizedGainsWorkbook();
    }

    /**
     * 背景排程用：指定 owner 的已實現損益匯出（Requirement 39 / Task 196）。
     * 背景執行緒無 request context，{@code TenantFilterAspect} 不啟用 → {@code findAll} 會讀到全部使用者的損益，
     * 故在本 session 手動啟用 {@code ownerFilter} 縮到該 owner，確保各使用者檔案只含自己的資料。
     */
    @Transactional(readOnly = true)
    public byte[] exportRealizedGainsForOwner(Long ownerId) throws IOException {
        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", ownerId);
        return buildRealizedGainsWorkbook();
    }

    /** 已實現損益活頁簿：單張「已實現損益」分頁，涵蓋全部年度（含 年度 欄）。 */
    private byte[] buildRealizedGainsWorkbook() throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            writeRealizedGainsSheet(wb, st);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 只匯出交易紀錄流水帳（含全部年度）（Requirement 49 / Task 237）。
     * HTTP 情境下由 {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped。
     * 手動下載、排程 run-now 皆走此方法，與背景排程產出同一份活頁簿。
     */
    @Transactional(readOnly = true)
    public byte[] exportAssetTransactions() throws IOException {
        return buildAssetTransactionsWorkbook();
    }

    /**
     * 背景排程用：指定 owner 的交易紀錄匯出（Requirement 49 / Task 238）。
     * 背景執行緒無 request context，{@code TenantFilterAspect} 不啟用 → {@code findAll} 會讀到全部使用者的交易，
     * 故在本 session 手動啟用 {@code ownerFilter} 縮到該 owner，確保各使用者檔案只含自己的資料。
     */
    @Transactional(readOnly = true)
    public byte[] exportAssetTransactionsForOwner(Long ownerId) throws IOException {
        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", ownerId);
        return buildAssetTransactionsWorkbook();
    }

    /** 交易紀錄活頁簿：單張「交易紀錄」分頁，涵蓋全部年度。 */
    private byte[] buildAssetTransactionsWorkbook() throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            writeAssetTransactionsSheet(wb, st);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 「交易紀錄」分頁（Requirement 49）：17 欄固定順序（Task 268 由 15 欄增為 17），涵蓋全部年度。
     * 台幣成交金額即時算（currency=USD 且 exchangeRate 非 null 時＝amount×exchangeRate，否則＝amount），不入庫。
     *
     * <p>手續費／證交稅（index 9、10）為純記錄欄，**不參與台幣成交金額計算**；未填時 {@link #cell}
     * 對 null 不寫值，該格為空白（與同表 shares／price／exchangeRate 的既有行為一致）。
     */
    private void writeAssetTransactionsSheet(Workbook wb, Styles st) {
        Sheet sheet = wb.createSheet("交易紀錄");
        int r = 0;

        Row h = sheet.createRow(r++);
        String[] headers = {"資產名稱","代號","交易類型","資產類型","交易日期","數量","單價","成交金額",
                "台幣成交金額","手續費","證交稅","市場","幣別","券商通路","匯率","年度","備註"};
        for (int i = 0; i < headers.length; i++) cell(h, i, headers[i], st.head);

        for (AssetTransaction tx : assetTxRepo.findAllByOrderByTradeDateDesc()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, tx.getAssetName(), null);
            cell(row, 1, tx.getAssetCode(), null);
            cell(row, 2, tx.getTransactionType(), null);
            cell(row, 3, tx.getAssetType(), null);
            cell(row, 4, tx.getTradeDate() != null ? ISO.format(tx.getTradeDate()) : "", null);
            cell(row, 5, tx.getShares(), st.num4);
            cell(row, 6, tx.getPrice(), st.num6);
            cell(row, 7, tx.getAmount(), st.money);
            cell(row, 8, assetTxAmountTwd(tx), st.money);
            cell(row, 9, tx.getFee(), st.money);
            cell(row, 10, tx.getTransactionTax(), st.money);
            cell(row, 11, tx.getMarket(), null);
            cell(row, 12, tx.getCurrency(), null);
            cell(row, 13, tx.getChannel(), null);
            cell(row, 14, tx.getExchangeRate(), st.num4);
            cell(row, 15, tx.getYear(), null);
            cell(row, 16, tx.getNotes(), null);
        }
        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
    }

    /** 台幣成交金額：USD 計價且有匯率時＝amount×exchangeRate，否則＝amount（與 AssetTransactionService.toResponse 同規則）。 */
    private static BigDecimal assetTxAmountTwd(AssetTransaction tx) {
        if ("USD".equals(tx.getCurrency()) && tx.getExchangeRate() != null && tx.getAmount() != null) {
            return tx.getAmount().multiply(tx.getExchangeRate());
        }
        return tx.getAmount();
    }

    /**
     * 油價金價區間匯出（Requirement 40 / Task 202）：單張工作表、油金同檔。
     * 全域公開行情，無 owner 過濾，故不需要 ForOwner 變體。
     */
    @Transactional(readOnly = true)
    public byte[] exportCommodityPrices(java.time.LocalDate start, java.time.LocalDate end) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            writeCommoditySheet(wb, st, start, end);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 「油價金價」分頁：日期／WTI／Brent／黃金四欄。
     *
     * 以日期為軸對三序列做 <b>outer join</b>（TreeMap 依日期排序）——WTI/Brent/黃金分屬 NYMEX 與 COMEX，
     * 假日與停牌日不完全重疊，若用 inner join 會漏掉「只有其中一個市場有報價」的日子。
     * 某標的當日無報價時該格留空，不補前值、不捏造。
     */
    private void writeCommoditySheet(Workbook wb, Styles st,
                                     java.time.LocalDate start, java.time.LocalDate end) {
        Sheet sheet = wb.createSheet("油價金價");

        Row h = sheet.createRow(0);
        cell(h, 0, "日期", st.head);
        cell(h, 1, "WTI原油(USD/桶)", st.head);
        cell(h, 2, "布蘭特原油(USD/桶)", st.head);
        cell(h, 3, "黃金(USD/盎司)", st.head);

        // 日期 → [WTI, BRENT, GOLD]，TreeMap 保證輸出依日期遞增
        java.util.TreeMap<java.time.LocalDate, BigDecimal[]> merged = new java.util.TreeMap<>();
        List<String> codes = HistoricalDataService.COMMODITY_CODES;
        for (int i = 0; i < codes.size(); i++) {
            final int col = i;
            for (CommodityPriceHistory p : commodityHistRepo
                    .findByCommodityCodeAndPriceDateBetweenOrderByPriceDateAsc(codes.get(i), start, end)) {
                merged.computeIfAbsent(p.getPriceDate(), k -> new BigDecimal[codes.size()])[col] = p.getClosePrice();
            }
        }

        int r = 1;
        for (Map.Entry<java.time.LocalDate, BigDecimal[]> e : merged.entrySet()) {
            Row row = sheet.createRow(r++);
            // 日期寫成文字：避免 Excel 依開啟端時區重新詮釋 date cell 而偏移一天
            cell(row, 0, ISO.format(e.getKey()), null);
            BigDecimal[] v = e.getValue();
            for (int i = 0; i < v.length; i++) {
                if (v[i] != null) cell(row, i + 1, v[i], st.num4);
            }
        }

        for (int i = 0; i < 4; i++) sheet.autoSizeColumn(i);
    }

    /**
     * 匯率匯出的顯示標籤（工作表名與檔名共用同一來源，避免兩處各自硬編碼而漂移）。
     *
     * <p>端點對外保留 {@code currency} 參數（與同檔其他匯率端點一致），故標籤必須跟著幣別走——
     * 若寫死「台幣兌美元」，帶 {@code currency=ZAR} 會匯出 ZAR 資料卻標成美元，是會在日後咬人的靜默誤標。
     * 前端目前恆送 USD，但那是「現在的呼叫者」的性質，不是這支 API 的契約。
     */
    public static String exchangeRateLabel(String currency) {
        return "USD".equalsIgnoreCase(currency) ? "台幣兌美元" : "台幣兌" + currency;
    }

    /**
     * 台幣兌美元匯率區間匯出（Requirement 42 / Task 204）：單張工作表。
     * 全域公開資料，無 owner 過濾，故不需要 ForOwner 變體（同油價金價）。
     */
    @Transactional(readOnly = true)
    public byte[] exportExchangeRates(String currency, java.time.LocalDate start, java.time.LocalDate end)
            throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            writeExchangeRateSheet(wb, st, currency, start, end);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 「台幣兌美元」分頁：日期／即期買入／即期賣出／中間價四欄，單一序列依日期遞增。
     *
     * <b>中間價必須由 entity 計算</b>：{@code mid_rate} 已於 v1.9.4 移除實體欄位（完整正規化：
     * 可由買入／賣出算出），{@link ExchangeRateHistory#getMidRate()} 為 {@code @Transient} getter。
     * 不可改以 JPQL/SQL 選取或 {@code ORDER BY mid_rate}——欄位不存在，會在 runtime 才炸。
     *
     * 買入／賣出當日無牌告時該格留空，不補前值、不捏造（同油價金價）。
     */
    private void writeExchangeRateSheet(Workbook wb, Styles st, String currency,
                                        java.time.LocalDate start, java.time.LocalDate end) {
        Sheet sheet = wb.createSheet(exchangeRateLabel(currency));

        Row h = sheet.createRow(0);
        cell(h, 0, "日期", st.head);
        cell(h, 1, "即期買入", st.head);
        cell(h, 2, "即期賣出", st.head);
        cell(h, 3, "中間價", st.head);

        int r = 1;
        for (ExchangeRateHistory e : rateHistRepo
                .findByCurrencyAndRateDateBetweenOrderByRateDateAsc(currency, start, end)) {
            Row row = sheet.createRow(r++);
            // 日期寫成文字：避免 Excel 依開啟端時區重新詮釋 date cell 而偏移一天
            cell(row, 0, ISO.format(e.getRateDate()), null);
            if (e.getBuyRate() != null) cell(row, 1, e.getBuyRate(), st.num4);
            if (e.getSellRate() != null) cell(row, 2, e.getSellRate(), st.num4);
            if (e.getMidRate() != null) cell(row, 3, e.getMidRate(), st.num4);
        }

        for (int i = 0; i < 4; i++) sheet.autoSizeColumn(i);
    }

    /**
     * 大盤指數匯出的顯示標籤（工作表名與兩種檔名共用同一來源，避免三處各自硬編碼中文名而漂移，
     * 理由同 {@link #exchangeRateLabel}）（Requirement 45 / Task 216）。
     *
     * <p>未知代碼直接以代碼本身為標籤，不臆造名稱——呼叫端已有白名單擋，此處只是不讓標籤說謊。
     * 前端 {@code GdpTwseView.MARKETS} 的 label 是 render 用，後端不可依賴前端字串。
     */
    public static String indexLabel(String market) {
        return switch (market == null ? "" : market.toUpperCase()) {
            case "TWSE" -> "台股大盤";
            case "DJI" -> "道瓊工業";
            case "SPX" -> "標普500";
            case "IXIC" -> "那斯達克綜合";
            case "SOX" -> "費城半導體";
            case "FTSE" -> "英國富時100";
            case "DAX" -> "德國DAX";
            case "KOSPI" -> "韓國KOSPI";
            case "N225" -> "日經225";
            default -> market == null ? "" : market;
        };
    }

    /**
     * 大盤指數日線區間匯出（Requirement 45 / Task 216）：單張工作表、日期／開高低收五欄。
     * 全域公開行情（兩張日線表皆無 owner 欄位、無 {@code @Filter}），故不需要 ForOwner 變體（同油價金價／匯率）。
     */
    @Transactional(readOnly = true)
    public byte[] exportIndexDaily(String market, java.time.LocalDate start, java.time.LocalDate end)
            throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            writeIndexDailySheet(wb, st, market, start, end);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 「大盤指數日線」分頁：日期／開盤／最高／最低／收盤五欄，單一序列依日期遞增。
     *
     * <p><b>四個價格欄直接讀 DB 既有 OHLC 欄位，不重算、不由收盤推導</b>——這與匯率分頁的中間價相反
     * （那是 {@code @Transient} 衍生值，必須由 entity 算）。兩張表的欄位語意相同，在此正規化成同一組
     * {@code (date, o, h, l, c)} 後共用同一段寫表邏輯，確保切換指數時版面一致。
     *
     * <p>{@code TWSE} 走 {@code twse_index_daily_history}、其餘走 {@code us_index_daily_history}；
     * 兩表的 open/high/low 皆 nullable（TWSE 早期由 v1.21.0 只抓 ClosingIndex 的殘留列），
     * null 該格留空、不補前值、不捏造（同油價金價／匯率）。日期寫成文字避免開啟端時區偏移一天。
     */
    private void writeIndexDailySheet(Workbook wb, Styles st, String market,
                                      java.time.LocalDate start, java.time.LocalDate end) {
        Sheet sheet = wb.createSheet(org.apache.poi.ss.util.WorkbookUtil
                .createSafeSheetName(indexLabel(market)));

        Row h = sheet.createRow(0);
        cell(h, 0, "日期", st.head);
        cell(h, 1, "開盤", st.head);
        cell(h, 2, "最高", st.head);
        cell(h, 3, "最低", st.head);
        cell(h, 4, "收盤", st.head);

        int r = 1;
        for (IndexDailyRow d : findIndexDaily(market, start, end)) {
            Row row = sheet.createRow(r++);
            cell(row, 0, ISO.format(d.date()), null);
            if (d.open() != null) cell(row, 1, d.open(), st.num4);
            if (d.high() != null) cell(row, 2, d.high(), st.num4);
            if (d.low() != null) cell(row, 3, d.low(), st.num4);
            cell(row, 4, d.close(), st.num4);
        }

        for (int i = 0; i < 5; i++) sheet.autoSizeColumn(i);
    }

    /** 兩張日線表正規化後的單日行情（僅供匯出寫表使用，不入庫）。 */
    private record IndexDailyRow(java.time.LocalDate date, BigDecimal open, BigDecimal high,
                                 BigDecimal low, BigDecimal close) {}

    /** 依 market 分派到對應日線表，回傳依日期遞增的正規化列。 */
    private List<IndexDailyRow> findIndexDaily(String market, java.time.LocalDate start, java.time.LocalDate end) {
        if ("TWSE".equalsIgnoreCase(market)) {
            return twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc(start, end).stream()
                    .map(t -> new IndexDailyRow(t.getTradingDate(), t.getOpenPoint(), t.getHighPoint(),
                            t.getLowPoint(), t.getClosePoint()))
                    .toList();
        }
        return usIndexHistRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(market, start, end).stream()
                .map(u -> new IndexDailyRow(u.getTradingDate(), u.getOpenPoint(), u.getHighPoint(),
                        u.getLowPoint(), u.getClosePoint()))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    private void writeSnapshotSheet(Workbook wb, Styles st, AssetSnapshot s) {
        String sheetName = SHEET_FMT.format(s.getSnapshotDate());
        // 同名重複時自動加後綴
        int suffix = 1;
        String unique = sheetName;
        while (wb.getSheet(unique) != null) {
            unique = sheetName + "_" + (++suffix);
        }
        Sheet sheet = wb.createSheet(unique);

        int r = 0;
        // 標頭
        Row h = sheet.createRow(r++);
        cell(h, 0, "日期", st.head);
        cell(h, 1, ISO.format(s.getSnapshotDate()), st.head);
        cell(h, 2, "美元匯率", st.head);
        cell(h, 3, s.getUsdExchangeRate(), st.num4);
        cell(h, 4, "總資產", st.head);
        cell(h, 5, s.getTotalAssets(), st.money);

        r++; // 空行

        // 銀行存款
        Row dh = sheet.createRow(r++);
        cell(dh, 0, "銀行存款", st.section);
        Row dh2 = sheet.createRow(r++);
        cell(dh2, 0, "銀行", st.head);
        cell(dh2, 1, "存款類型", st.head);
        cell(dh2, 2, "幣別", st.head);
        cell(dh2, 3, "原幣金額", st.head);
        cell(dh2, 4, "台幣金額", st.head);
        cell(dh2, 5, "備註", st.head);
        for (BankDeposit d : s.getDeposits()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, d.getBank() != null ? d.getBank().getDisplayName() : "", null);
            cell(row, 1, d.getDepositType(), null);
            cell(row, 2, d.getCurrency(), null);
            cell(row, 3, d.getOriginalAmount(), st.money);
            cell(row, 4, d.getAmount(), st.money);
            cell(row, 5, d.getNotes(), null);
        }

        r++;

        // 基金
        Row fh = sheet.createRow(r++);
        cell(fh, 0, "基金", st.section);
        Row fh2 = sheet.createRow(r++);
        cell(fh2, 0, "銀行", st.head);
        cell(fh2, 1, "基金名稱", st.head);
        cell(fh2, 2, "投入成本", st.head);
        cell(fh2, 3, "現值", st.head);
        for (FundHolding f : s.getFunds()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, f.getBank() != null ? f.getBank().getDisplayName() : "", null);
            cell(row, 1, f.getFundName(), null);
            cell(row, 2, f.getInvestmentAmount(), st.money);
            cell(row, 3, f.getCurrentValue(), st.money);
        }

        r++;

        // 股票
        Row sh = sheet.createRow(r++);
        cell(sh, 0, "股票", st.section);
        Row sh2 = sheet.createRow(r++);
        cell(sh2, 0, "券商", st.head);
        cell(sh2, 1, "市場", st.head);
        cell(sh2, 2, "代號", st.head);
        cell(sh2, 3, "名稱", st.head);
        cell(sh2, 4, "股數", st.head);
        cell(sh2, 5, "投資成本", st.head);
        cell(sh2, 6, "現值", st.head);
        cell(sh2, 7, "預估配息", st.head);
        cell(sh2, 8, "交易類型", st.head);
        cell(sh2, 9, "交易日期", st.head);
        for (StockHolding sk : s.getStocks()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, sk.getBroker() != null ? sk.getBroker().getDisplayName() : "", null);
            cell(row, 1, sk.getMarket(), null);
            cell(row, 2, sk.getStockCode(), null);
            String stName = stockMasterRepo.findByCodeAndMarket(sk.getStockCode(), sk.getMarket())
                    .map(Stock::getName).orElse(sk.getStockCode());
            cell(row, 3, stName, null);
            cell(row, 4, sk.getShares(), st.num4);
            // 投資成本一律輸出台幣：美股 USD 計價列依交易日匯率（無則快照匯率）換算，避免把美元當台幣匯出
            cell(row, 5, stockCostTwd(sk, s.getUsdExchangeRate()), st.money);
            cell(row, 6, sk.getCurrentValue(), st.money);
            cell(row, 7, sk.getEstimatedDividend(), st.money);
            cell(row, 8, sk.getTransactionType(), null);
            cell(row, 9, sk.getTransactionDate() != null ? ISO.format(sk.getTransactionDate()) : "", null);
        }

        for (int i = 0; i < 10; i++) sheet.autoSizeColumn(i);
    }

    /**
     * 「當前即時資產」分頁（Requirement 34 增修）：排版比照 {@link #writeSnapshotSheet}，
     * 但股票以 Redis 即時價重估（{@code live}，來自 {@link StockPriceService#getLiveAssets()}）。
     * deposits／funds 明細讀最新快照 {@code s}；股票逐檔以 {@code (code|market)} 對映即時價／即時現值，
     * 查無即時價時 fallback 快照凍結現值。
     */
    private void writeLiveAssetsSheet(Workbook wb, Styles st,
                                      StockPriceService.LiveAssetsResponse live, AssetSnapshot s) {
        Sheet sheet = wb.createSheet("當前即時資產");
        int r = 0;

        Row title = sheet.createRow(r++);
        cell(title, 0, "當前即時資產", st.section);

        Row exp = sheet.createRow(r++);
        cell(exp, 0, "匯出時間", st.head);
        cell(exp, 1, LocalDateTime.now(TW_ZONE).format(TS_FMT), null);

        if (s == null || live == null) {
            Row none = sheet.createRow(r++);
            cell(none, 0, "尚無資產快照", null);
            for (int i = 0; i < 6; i++) sheet.autoSizeColumn(i);
            return;
        }

        BigDecimal exchangeRate = live.exchangeRate();

        Row h = sheet.createRow(r++);
        cell(h, 0, "基準快照日期", st.head);
        cell(h, 1, ISO.format(s.getSnapshotDate()), null);
        cell(h, 2, "美元匯率", st.head);
        cell(h, 3, exchangeRate, st.num4);
        cell(h, 4, "即時總資產", st.head);
        cell(h, 5, live.liveTotalAssets(), st.money);

        r++; // 空行

        // 即時彙總
        Row secSum = sheet.createRow(r++);
        cell(secSum, 0, "即時彙總", st.section);
        r = summaryRow(sheet, st, r, "存款總計", live.totalDeposit());
        r = summaryRow(sheet, st, r, "基金現值", live.totalFundValue());
        r = summaryRow(sheet, st, r, "即時股票現值", live.liveStockValue());
        r = summaryRow(sheet, st, r, "即時總資產", live.liveTotalAssets());

        r++;

        // 銀行存款（讀最新快照）
        Row dh = sheet.createRow(r++);
        cell(dh, 0, "銀行存款", st.section);
        Row dh2 = sheet.createRow(r++);
        cell(dh2, 0, "銀行", st.head);
        cell(dh2, 1, "存款類型", st.head);
        cell(dh2, 2, "幣別", st.head);
        cell(dh2, 3, "原幣金額", st.head);
        cell(dh2, 4, "台幣金額", st.head);
        cell(dh2, 5, "備註", st.head);
        for (BankDeposit d : s.getDeposits()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, d.getBank() != null ? d.getBank().getDisplayName() : "", null);
            cell(row, 1, d.getDepositType(), null);
            cell(row, 2, d.getCurrency(), null);
            cell(row, 3, d.getOriginalAmount(), st.money);
            cell(row, 4, d.getAmount(), st.money);
            cell(row, 5, d.getNotes(), null);
        }

        r++;

        // 基金（讀最新快照）
        Row fh = sheet.createRow(r++);
        cell(fh, 0, "基金", st.section);
        Row fh2 = sheet.createRow(r++);
        cell(fh2, 0, "銀行", st.head);
        cell(fh2, 1, "基金名稱", st.head);
        cell(fh2, 2, "投入成本", st.head);
        cell(fh2, 3, "現值", st.head);
        for (FundHolding f : s.getFunds()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, f.getBank() != null ? f.getBank().getDisplayName() : "", null);
            cell(row, 1, f.getFundName(), null);
            cell(row, 2, f.getInvestmentAmount(), st.money);
            cell(row, 3, f.getCurrentValue(), st.money);
        }

        r++;

        // 股票（即時價 + 即時現值）
        Map<String, StockPriceService.LiveStockItem> liveMap = new HashMap<>();
        for (StockPriceService.LiveStockItem it : live.stocks()) {
            liveMap.put(it.stockCode() + "|" + it.market(), it);
        }

        Row sh = sheet.createRow(r++);
        cell(sh, 0, "股票（即時）", st.section);
        Row sh2 = sheet.createRow(r++);
        cell(sh2, 0, "券商", st.head);
        cell(sh2, 1, "市場", st.head);
        cell(sh2, 2, "代號", st.head);
        cell(sh2, 3, "名稱", st.head);
        cell(sh2, 4, "股數", st.head);
        cell(sh2, 5, "投資成本", st.head);
        cell(sh2, 6, "即時價", st.head);
        cell(sh2, 7, "昨收", st.head);
        cell(sh2, 8, "漲跌", st.head);
        cell(sh2, 9, "漲跌幅(%)", st.head);
        cell(sh2, 10, "即時現值", st.head);
        cell(sh2, 11, "預估配息", st.head);
        cell(sh2, 12, "交易類型", st.head);
        cell(sh2, 13, "交易日期", st.head);
        cell(sh2, 14, "月線價", st.head);
        cell(sh2, 15, "季線價", st.head);
        cell(sh2, 16, "年線價", st.head);
        cell(sh2, 17, "KD值", st.head);
        // ETF 淨值／折溢價（Task 214）：個股無淨值故留白，見 writeLiveAssetsSheet 逐列註解
        cell(sh2, 18, "淨值", st.head);
        cell(sh2, 19, "折溢價(%)", st.head);
        cell(sh2, 20, "淨值時間", st.head);
        // 技術指標（月/季/年線、KD）逐 (code|market) 快取：同股多券商列僅算一次（Task 200）
        Map<String, TechnicalIndicatorService.FullIndicators> indicatorCache = new HashMap<>();
        // ETF 淨值／折溢價逐 (code|market) 快取（Task 214）；查無者快取 null，避免同檔多列重複讀 Redis
        Map<String, PriceQueryService.EtfNav> navCache = new HashMap<>();
        for (StockHolding sk : s.getStocks()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, sk.getBroker() != null ? sk.getBroker().getDisplayName() : "", null);
            cell(row, 1, sk.getMarket(), null);
            cell(row, 2, sk.getStockCode(), null);
            StockPriceService.LiveStockItem it = liveMap.get(sk.getStockCode() + "|" + sk.getMarket());
            String stName = it != null ? it.stockName()
                    : stockMasterRepo.findByCodeAndMarket(sk.getStockCode(), sk.getMarket())
                        .map(Stock::getName).orElse(sk.getStockCode());
            cell(row, 3, stName, null);
            cell(row, 4, sk.getShares(), st.num4);
            cell(row, 5, stockCostTwd(sk, exchangeRate), st.money);
            BigDecimal livePrice = it != null ? it.currentPrice() : null;
            cell(row, 6, livePrice, st.num4);
            // 昨收／漲跌／漲跌幅：與即時價同一筆 LivePrice（同一 tick），故「即時價 − 昨收 = 漲跌」自洽
            cell(row, 7, it != null ? it.previousClose() : null, st.num4);
            cell(row, 8, it != null ? it.priceChange() : null, st.num2);
            cell(row, 9, it != null ? it.changePercent() : null, st.num2);
            // 即時現值 per-holding：即時價 × 該列股數（美股/英股再 × 匯率），比照 getLiveAssets 每檔算法。
            // 不可直接取 LiveStockItem.liveValue：同一 code|market 多筆持股（不同券商）在 liveMap 會互相覆蓋。
            cell(row, 10, liveStockValueTwd(sk, livePrice, exchangeRate), st.money);
            cell(row, 11, sk.getEstimatedDividend(), st.money);
            cell(row, 12, sk.getTransactionType(), null);
            cell(row, 13, sk.getTransactionDate() != null ? ISO.format(sk.getTransactionDate()) : "", null);
            // 月/季/年線與 KD：共用權威 TechnicalIndicatorService（與觀察清單／警示同一計算），逐 code|market 快取
            TechnicalIndicatorService.FullIndicators ind = indicatorCache.computeIfAbsent(
                    sk.getStockCode() + "|" + sk.getMarket(),
                    k -> technicalIndicatorService.computeAll(sk.getStockCode(), sk.getMarket()));
            cell(row, 14, ind.monthlyMa(), st.num2);
            cell(row, 15, ind.quarterlyMa(), st.num2);
            cell(row, 16, ind.annualMa(), st.num2);
            cell(row, 17, formatKd(ind.k(), ind.d()), null);
            // ETF 淨值／折溢價（Task 214）：資料驅動——Redis 有值才印，個股（無淨值）與抓取失敗皆自然留白。
            // 刻意不做 isEtf 白名單判定（既有白名單誤含個股 AVGO、又漏掉持有的 SGOV）。
            // 同一檔多券商多列共用同一筆，比照技術指標以 code|market 快取，每檔只讀一次 Redis。
            // 用 containsKey 而非 computeIfAbsent：後者不會快取 null 值，個股（永遠查無）會逐列重讀 Redis
            String navKey = sk.getStockCode() + "|" + sk.getMarket();
            if (!navCache.containsKey(navKey)) {
                navCache.put(navKey, priceQueryService
                        .getEtfNav(sk.getStockCode(), sk.getMarket()).orElse(null));
            }
            PriceQueryService.EtfNav nav = navCache.get(navKey);
            cell(row, 18, nav == null ? null : nav.nav(), st.num4);
            cell(row, 19, premiumDiscountPct(nav, livePrice), st.num2);
            cell(row, 20, nav == null ? null : nav.navAsOf(), null);
        }

        for (int i = 0; i < 21; i++) sheet.autoSizeColumn(i);
    }

    /**
     * 每檔持股一張「過去一年股價」分頁（Task 206）：分頁名＝股票代號，接在「當前即時資產」總表之後。
     *
     * <p>持股清單取自與總表<b>同一個</b> {@code s}（同交易 lazy load，不另查），以 {@code (code|market)} 去重
     * ——同一檔分散多家券商只出一張分頁——並保留總表由上而下的順序。{@code s} 為 null（尚無快照）時不產生分頁。
     * owner 隔離沿用外層（HTTP 走 aspect、背景排程走手動 {@code enableFilter}），故只含使用者自己持有的股票。
     */
    private void writeStockPriceHistorySheets(Workbook wb, Styles st, AssetSnapshot s) {
        if (s == null) return;
        java.time.LocalDate end = java.time.LocalDate.now(TW_ZONE);
        java.time.LocalDate start = end.minusYears(1);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (StockHolding sk : s.getStocks()) {
            String code = sk.getStockCode();
            if (code == null || code.isBlank()) continue;
            if (!seen.add(code + "|" + sk.getMarket())) continue; // 同檔多券商只出一張
            writeStockPriceHistorySheet(wb, st, code, sk.getMarket(), start, end);
        }
    }

    /**
     * 單一個股的「過去一年股價」分頁：表頭 ＋ 逐交易日一列（日期遞增），資料源 {@code stock_price_history}
     * （收盤價唯一權威來源，與 {@link TechnicalIndicatorService} 的 MA／KD 同源，匯出過程零外部行情呼叫）。
     *
     * <p>日期寫成文字（同油價金價／匯率分頁：避免 Excel 依開啟端時區重新詮釋 date cell 而偏移一天）。
     * 開高低與成交量在 DB 可空（僅 {@code close_price} NOT NULL），該格留白不補值。
     * <b>區間內查無資料仍建立只有表頭的空分頁</b>，讓「持有但無資料」與「未持有」可區分，不靜默略過。
     */
    private void writeStockPriceHistorySheet(Workbook wb, Styles st, String code, String market,
                                             java.time.LocalDate start, java.time.LocalDate end) {
        Sheet sheet = wb.createSheet(uniqueStockSheetName(wb, code, market));

        Row h = sheet.createRow(0);
        cell(h, 0, "日期", st.head);
        cell(h, 1, "開盤價", st.head);
        cell(h, 2, "最高價", st.head);
        cell(h, 3, "最低價", st.head);
        cell(h, 4, "收盤價", st.head);
        cell(h, 5, "成交量", st.head);

        int r = 1;
        for (StockPriceHistory p : priceHistRepo
                .findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(code, market, start, end)) {
            Row row = sheet.createRow(r++);
            cell(row, 0, ISO.format(p.getTradingDate()), null);
            cell(row, 1, p.getOpenPrice(), st.num4);
            cell(row, 2, p.getHighPrice(), st.num4);
            cell(row, 3, p.getLowPrice(), st.num4);
            cell(row, 4, p.getClosePrice(), st.num4);
            cell(row, 5, p.getVolume(), null); // 整數股數／張數，不套小數樣式
        }

        for (int i = 0; i < 6; i++) sheet.autoSizeColumn(i);
    }

    /**
     * 股價分頁名稱：首選股票代號，經 {@code createSafeSheetName} 收斂 Excel 限制（31 字元上限、禁 {@code []:*?/\}）。
     * 不同市場出現同一代號時改 {@code 代號_市場}；仍衝突再加數字後綴（比照 {@link #writeSnapshotSheet}）。
     */
    private static String uniqueStockSheetName(Workbook wb, String code, String market) {
        String base = org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(code);
        if (wb.getSheet(base) == null) return base;
        String withMarket = org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(code + "_" + market);
        if (wb.getSheet(withMarket) == null) return withMarket;
        int suffix = 1;
        String unique;
        do {
            unique = org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(withMarket + "_" + (++suffix));
        } while (wb.getSheet(unique) != null);
        return unique;
    }

    /**
     * 折溢價%（Task 214）：來源已提供權威值就直接用，否則以<b>本列顯示的即時價</b>與淨值計算。
     *
     * <p>兩條路徑的理由不同，不可統一：
     * <ul>
     *   <li><b>台股</b>：證交所已算好折溢價，且其市價與本列「即時價」同源（皆為 TWSE mis 的成交價，實測逐檔吻合），
     *       故直接沿用權威值。<b>不得改為自行重算</b>——證交所的淨值欄在股票型 ETF 四捨五入至小數 2 位，
     *       重算誤差可達 0.07 個百分點（Requirement 34／Task 259 起：台股折溢價欄留白時<b>一律回 null</b>，
     *       不得以本列即時價反推）。</li>
     *   <li><b>美股</b>：Yahoo 未提供折溢價欄。若在抓取端以 Yahoo 自己的市價計算，會與本列「即時價」
     *       （走 Redis，來源與時點皆不同）對不起來——實測 VOO 兩者相差 0.11%，使用者拿本列數字驗算會兜不攏。
     *       故改在此以該列自己的即時價計算，保證列內自洽。</li>
     * </ul>
     * 淨值或即時價任一缺漏即回 null（留白），不以昨收等替代值湊數。
     */
    static BigDecimal premiumDiscountPct(PriceQueryService.EtfNav nav, BigDecimal livePrice) {
        if (nav == null) return null;
        if (nav.premiumDiscountPct() != null) return nav.premiumDiscountPct();
        if ("台股".equals(nav.market())) return null; // Task 259：台股折溢價缺漏不反推
        if (livePrice == null || nav.nav() == null || nav.nav().compareTo(BigDecimal.ZERO) == 0) return null;
        return livePrice.subtract(nav.nav())
                .multiply(BigDecimal.valueOf(100))
                .divide(nav.nav(), 2, java.math.RoundingMode.HALF_UP);
    }

    /** KD 併為單一「KD值」欄字串 "K {k} / D {d}"；兩者皆 null 回 null（留白），單邊 null 以「—」佔位。 */
    private static String formatKd(BigDecimal k, BigDecimal d) {
        if (k == null && d == null) return null;
        return "K " + (k != null ? k.toPlainString() : "—")
             + " / D " + (d != null ? d.toPlainString() : "—");
    }

    private void writeRealizedGainsSheet(Workbook wb, Styles st) {
        Sheet sheet = wb.createSheet("已實現損益");
        int r = 0;

        Row h = sheet.createRow(r++);
        String[] headers = {"資產名稱","代號","交易日期","股數","賣出均價","收帳金額","投資成本","損益","報酬率",
                "市場","幣別","券商","匯率","年度"};
        for (int i = 0; i < headers.length; i++) cell(h, i, headers[i], st.head);

        for (RealizedGain g : gainRepo.findAllByOrderByTradeDateDesc()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, g.getAssetName(), null);
            cell(row, 1, g.getAssetCode(), null);
            cell(row, 2, g.getTradeDate() != null ? ISO.format(g.getTradeDate()) : "", null);
            cell(row, 3, g.getShares(), st.num4);
            cell(row, 4, g.getSalePrice(), st.num4);
            cell(row, 5, g.getProceeds(), st.money);
            cell(row, 6, g.getInvestmentCost(), st.money);
            BigDecimal profit = g.getProceeds().subtract(g.getInvestmentCost());
            BigDecimal profitRate = g.getInvestmentCost().compareTo(BigDecimal.ZERO) != 0
                    ? profit.divide(g.getInvestmentCost(), 6, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            cell(row, 7, profit, st.money);
            cell(row, 8, profitRate, st.num4);
            cell(row, 9, g.getMarket(), null);
            cell(row, 10, g.getCurrency(), null);
            cell(row, 11, g.getBroker(), null);
            cell(row, 12, g.getExchangeRate(), st.num4);
            cell(row, 13, g.getYear(), null);
        }
        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
    }

    // ─────────────────────────────────────────────────────────────────────────
    private void cell(Row row, int col, Object value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value == null) return;
        if (value instanceof BigDecimal bd) c.setCellValue(bd.doubleValue());
        else if (value instanceof Number n) c.setCellValue(n.doubleValue());
        else c.setCellValue(value.toString());
        if (style != null) c.setCellStyle(style);
    }

    private static class Styles {
        final CellStyle head;
        final CellStyle section;
        final CellStyle money;
        final CellStyle num4;
        final CellStyle num2;
        final CellStyle num6;

        Styles(Workbook wb) {
            DataFormat fmt = wb.createDataFormat();

            Font headFont = wb.createFont();
            headFont.setBold(true);
            head = wb.createCellStyle();
            head.setFont(headFont);
            head.setAlignment(HorizontalAlignment.CENTER);

            Font secFont = wb.createFont();
            secFont.setBold(true);
            secFont.setFontHeightInPoints((short) 13);
            section = wb.createCellStyle();
            section.setFont(secFont);

            money = wb.createCellStyle();
            money.setDataFormat(fmt.getFormat("#,##0.00"));

            num4 = wb.createCellStyle();
            num4.setDataFormat(fmt.getFormat("#,##0.0000"));

            // 2 位小數：漲跌／漲跌幅(%)／月線／季線／年線（Task 200）
            num2 = wb.createCellStyle();
            num2.setDataFormat(fmt.getFormat("#,##0.00"));

            // 6 位小數：交易紀錄單價（Task 239）
            num6 = wb.createCellStyle();
            num6.setDataFormat(fmt.getFormat("#,##0.000000"));
        }
    }
}
