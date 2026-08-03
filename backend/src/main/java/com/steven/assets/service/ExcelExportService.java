package com.steven.assets.service;

import com.steven.assets.model.*;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.RealizedGainRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.service.export.ExportDoc;
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
    // 雙格式匯出（Requirement 55 / Task 270）：分頁改建 ExportDoc，xlsx 由 renderer 產出，
    // 排程端再以同一份 doc render 出 JSON——「一次查詢、兩種格式」是兩份檔內容一致的唯一保證。
    private final com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer;

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
        return excelDocRenderer.render(liveAssetsDoc());
    }

    /**
     * 背景排程用：指定 owner 的「當前即時資產」匯出。背景執行緒無 request context，
     * {@code TenantFilterAspect} 不啟用，故在本 session 手動 {@code enableFilter} 縮到該 owner，
     * 讓 {@code getLiveAssets()} 與最新快照查詢都只含該使用者資產。
     */
    @Transactional(readOnly = true)
    public byte[] exportLiveAssetsForOwner(Long ownerId) throws IOException {
        return excelDocRenderer.render(liveAssetsDocForOwner(ownerId));
    }

    /**
     * 當前即時資產活頁簿：第一張「當前即時資產」總表（股票即時價，存款／基金讀最新快照），
     * 第二張起每檔持股一張「過去一年股價」分頁（分頁名＝股票代號，Task 206）。
     */
    /**
     * 當前即時資產的 {@link ExportDoc}（Requirement 55 / Task 271）。
     *
     * <p>排程端<b>只呼叫一次</b>取得 doc，再 render 成 xlsx 與 JSON——本匯出點吃 Redis 即時價，
     * 呼叫兩次等於查兩次，兩份檔的數字會對不起來且無從得知哪一份才是對的。
     */
    @Transactional(readOnly = true)
    public ExportDoc liveAssetsDoc() {
        // 即時股票估值單一來源：與 Dashboard「當前資產」同一 getLiveAssets()（同一 session/交易，filter 生效）。
        StockPriceService.LiveAssetsResponse live = stockPriceService.getLiveAssets();
        AssetSnapshot latest = snapshotRepo.findLatest().orElse(null); // deposits/funds 明細於同交易 lazy load
        List<ExportDoc.Sheet> sheets = new java.util.ArrayList<>();
        sheets.add(liveAssetsSheet(live, latest));
        sheets.addAll(stockPriceHistorySheets(latest)); // 持股清單與總表同一 latest，兩者必然一致
        return new ExportDoc("資產總覽", sheets);
    }

    /** 背景排程用：指定 owner 的當前即時資產 doc（owner-scoped，理由同 {@link #exportLiveAssetsForOwner}）。 */
    @Transactional(readOnly = true)
    public ExportDoc liveAssetsDocForOwner(Long ownerId) {
        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", ownerId);
        return liveAssetsDoc();
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
            // 已實現損益分頁與排程匯出的那一份是同一個來源（doc），不得分裂成兩份實作
            excelDocRenderer.writeSheet(wb, realizedGainsSheet());
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
        return excelDocRenderer.render(realizedGainsDoc());
    }

    /**
     * 已實現損益的 {@link ExportDoc}（Requirement 55 / Task 270）。
     *
     * <p>排程端<b>只呼叫一次</b>取得 doc，再 render 成 xlsx 與 JSON 兩種格式——
     * 呼叫兩次等於查兩次資料，兩份檔的內容可能對不起來。
     */
    @Transactional(readOnly = true)
    public ExportDoc realizedGainsDoc() {
        return new ExportDoc("已實現損益", List.of(realizedGainsSheet()));
    }

    /** 背景排程用：指定 owner 的已實現損益 doc（owner-scoped，理由同 {@link #exportRealizedGainsForOwner}）。 */
    @Transactional(readOnly = true)
    public ExportDoc realizedGainsDocForOwner(Long ownerId) {
        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", ownerId);
        return realizedGainsDoc();
    }

    /**
     * 背景排程用：指定 owner 的已實現損益匯出（Requirement 39 / Task 196）。
     * 背景執行緒無 request context，{@code TenantFilterAspect} 不啟用 → {@code findAll} 會讀到全部使用者的損益，
     * 故在本 session 手動啟用 {@code ownerFilter} 縮到該 owner，確保各使用者檔案只含自己的資料。
     */
    @Transactional(readOnly = true)
    public byte[] exportRealizedGainsForOwner(Long ownerId) throws IOException {
        return excelDocRenderer.render(realizedGainsDocForOwner(ownerId));
    }

    /**
     * 只匯出交易紀錄流水帳（含全部年度）（Requirement 49 / Task 237）。
     * HTTP 情境下由 {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped。
     * 手動下載、排程 run-now 皆走此方法，與背景排程產出同一份活頁簿。
     */
    @Transactional(readOnly = true)
    public byte[] exportAssetTransactions() throws IOException {
        return excelDocRenderer.render(assetTransactionsDoc());
    }

    /** 交易紀錄的 {@link ExportDoc}（Requirement 55 / Task 270）；排程端只取一次再 render 兩種格式。 */
    @Transactional(readOnly = true)
    public ExportDoc assetTransactionsDoc() {
        return new ExportDoc("交易紀錄", List.of(assetTransactionsSheet()));
    }

    /** 背景排程用：指定 owner 的交易紀錄 doc（owner-scoped，理由同 {@link #exportAssetTransactionsForOwner}）。 */
    @Transactional(readOnly = true)
    public ExportDoc assetTransactionsDocForOwner(Long ownerId) {
        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", ownerId);
        return assetTransactionsDoc();
    }

    /**
     * 背景排程用：指定 owner 的交易紀錄匯出（Requirement 49 / Task 238）。
     * 背景執行緒無 request context，{@code TenantFilterAspect} 不啟用 → {@code findAll} 會讀到全部使用者的交易，
     * 故在本 session 手動啟用 {@code ownerFilter} 縮到該 owner，確保各使用者檔案只含自己的資料。
     */
    @Transactional(readOnly = true)
    public byte[] exportAssetTransactionsForOwner(Long ownerId) throws IOException {
        return excelDocRenderer.render(assetTransactionsDocForOwner(ownerId));
    }

    /**
     * 「交易紀錄」分頁（Requirement 49）：17 欄固定順序（Task 268 由 15 欄增為 17），涵蓋全部年度。
     * 台幣成交金額即時算（currency=USD 且 exchangeRate 非 null 時＝amount×exchangeRate，否則＝amount），不入庫。
     *
     * <p>手續費／證交稅（index 9、10）為純記錄欄，**不參與台幣成交金額計算**；未填時 {@link #cell}
     * 對 null 不寫值，該格為空白（與同表 shares／price／exchangeRate 的既有行為一致）。
     */
    private ExportDoc.Sheet assetTransactionsSheet() {
        List<String> headers = List.of("資產名稱","代號","交易類型","資產類型","交易日期","數量","單價",
                "成交金額","台幣成交金額","手續費","證交稅","市場","幣別","券商通路","匯率","年度","備註");
        List<ExportDoc.Format> formats = List.of(
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM4,
                ExportDoc.Format.NUM6, ExportDoc.Format.MONEY, ExportDoc.Format.MONEY,
                // 手續費／證交稅（Task 268，index 9、10）：對齊 main 的 st.money
                ExportDoc.Format.MONEY, ExportDoc.Format.MONEY,
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                ExportDoc.Format.NUM4, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT);

        List<List<Object>> rows = new java.util.ArrayList<>();
        for (AssetTransaction tx : assetTxRepo.findAllByOrderByTradeDateDesc()) {
            rows.add(java.util.Arrays.asList(
                    tx.getAssetName(),
                    tx.getAssetCode(),
                    tx.getTransactionType(),
                    tx.getAssetType(),
                    // 既有寫空字串而非 null（具名例外二），不可改成 null
                    tx.getTradeDate() != null ? ISO.format(tx.getTradeDate()) : "",
                    tx.getShares(),
                    tx.getPrice(),
                    tx.getAmount(),
                    assetTxAmountTwd(tx),
                    // 純記錄欄，不參與台幣成交金額；null＝沒記費用、0＝確實免收，兩者語意不同不得互轉，
                    // 故一律原值傳下去（omitNullCells=false → null 產生無樣式的 BLANK 格，與 main 的 cell() 一致）
                    tx.getFee(),
                    tx.getTransactionTax(),
                    tx.getMarket(),
                    tx.getCurrency(),
                    tx.getChannel(),
                    tx.getExchangeRate(),
                    tx.getYear(),
                    tx.getNotes()));
        }
        return new ExportDoc.Sheet("交易紀錄",
                List.of(new ExportDoc.Table(null, null, headers, true, false, false, formats, rows)),
                headers.size());
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
        return excelDocRenderer.render(commodityPricesDoc(start, end));
    }

    /** 油價金價的 {@link ExportDoc}（Requirement 55 / Task 270）；排程端只取一次再 render 兩種格式。 */
    @Transactional(readOnly = true)
    public ExportDoc commodityPricesDoc(java.time.LocalDate start, java.time.LocalDate end) {
        return new ExportDoc("油價金價", List.of(commoditySheet(start, end)));
    }

    /**
     * 「油價金價」分頁：日期／WTI／Brent／黃金四欄。
     *
     * 以日期為軸對三序列做 <b>outer join</b>（TreeMap 依日期排序）——WTI/Brent/黃金分屬 NYMEX 與 COMEX，
     * 假日與停牌日不完全重疊，若用 inner join 會漏掉「只有其中一個市場有報價」的日子。
     * 某標的當日無報價時該格留空，不補前值、不捏造。
     */
    /**
     * 「油價金價」分頁的 {@link ExportDoc.Sheet}。
     *
     * <p><b>{@code omitNullCells = true}</b>：既有寫法是 {@code if (v[i] != null) cell(...)}——
     * 值為 null 時那一格<b>根本不建</b>（不是 BLANK 格）。改成 BLANK 會讓該列
     * {@code getLastCellNum()} 變大，是可觀測的版面變動。
     */
    private ExportDoc.Sheet commoditySheet(java.time.LocalDate start, java.time.LocalDate end) {
        List<String> headers = List.of("日期", "WTI原油(USD/桶)", "布蘭特原油(USD/桶)", "黃金(USD/盎司)");
        List<ExportDoc.Format> formats = List.of(ExportDoc.Format.DATE,
                ExportDoc.Format.NUM4, ExportDoc.Format.NUM4, ExportDoc.Format.NUM4);

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

        List<List<Object>> rows = new java.util.ArrayList<>();
        for (Map.Entry<java.time.LocalDate, BigDecimal[]> e : merged.entrySet()) {
            List<Object> row = new java.util.ArrayList<>();
            // Format.DATE 會寫成 ISO 文字 cell：避免 Excel 依開啟端時區重新詮釋 date cell 而偏移一天
            row.add(e.getKey());
            for (BigDecimal v : e.getValue()) row.add(v);
            rows.add(row);
        }
        return new ExportDoc.Sheet("油價金價",
                List.of(new ExportDoc.Table(null, null, headers, true, false, true, formats, rows)),
                headers.size());
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
        return excelDocRenderer.render(exchangeRatesDoc(currency, start, end));
    }

    /** 台幣兌外幣匯率的 {@link ExportDoc}（Requirement 55 / Task 270）；排程端只取一次再 render 兩種格式。 */
    @Transactional(readOnly = true)
    public ExportDoc exchangeRatesDoc(String currency, java.time.LocalDate start, java.time.LocalDate end) {
        return new ExportDoc(exchangeRateLabel(currency), List.of(exchangeRateSheet(currency, start, end)));
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
    private ExportDoc.Sheet exchangeRateSheet(String currency,
                                              java.time.LocalDate start, java.time.LocalDate end) {
        List<String> headers = List.of("日期", "即期買入", "即期賣出", "中間價");
        List<ExportDoc.Format> formats = List.of(ExportDoc.Format.DATE,
                ExportDoc.Format.NUM4, ExportDoc.Format.NUM4, ExportDoc.Format.NUM4);

        List<List<Object>> rows = new java.util.ArrayList<>();
        for (ExchangeRateHistory e : rateHistRepo
                .findByCurrencyAndRateDateBetweenOrderByRateDateAsc(currency, start, end)) {
            // Format.DATE 會寫成 ISO 文字 cell：避免 Excel 依開啟端時區重新詮釋 date cell 而偏移一天
            rows.add(java.util.Arrays.asList(
                    e.getRateDate(), e.getBuyRate(), e.getSellRate(), e.getMidRate()));
        }
        // omitNullCells=true：既有 if (x != null) cell(...)，缺牌告時該格根本不建（不是 BLANK 格）
        return new ExportDoc.Sheet(exchangeRateLabel(currency),
                List.of(new ExportDoc.Table(null, null, headers, true, false, true, formats, rows)),
                headers.size());
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
     * 大盤指數日線區間匯出（Requirement 45 / Task 216）：單張工作表、日期／開高低收 ＋ 四條均線九欄
     * （第六～九欄為 Task 284／285 新增的計算欄：週線MA5／月線MA20／季線MA60／年線MA240）。
     * 全域公開行情（兩張日線表皆無 owner 欄位、無 {@code @Filter}），故不需要 ForOwner 變體（同油價金價／匯率）。
     */
    @Transactional(readOnly = true)
    public byte[] exportIndexDaily(String market, java.time.LocalDate start, java.time.LocalDate end)
            throws IOException {
        return excelDocRenderer.render(indexDailyDoc(market, start, end));
    }

    /** 大盤指數日線的 {@link ExportDoc}（Requirement 55 / Task 270）；排程端只取一次再 render 兩種格式。 */
    @Transactional(readOnly = true)
    public ExportDoc indexDailyDoc(String market, java.time.LocalDate start, java.time.LocalDate end) {
        return new ExportDoc(indexLabel(market), List.of(indexDailySheet(market, start, end)));
    }

    /**
     * 「大盤指數日線」分頁：日期／開盤／最高／最低／收盤／週線MA5／月線MA20／季線MA60／年線MA240
     * 九欄，單一序列依日期遞增。
     *
     * <p><b>四個價格欄直接讀 DB 既有 OHLC 欄位，不重算、不由收盤推導</b>——這與匯率分頁的中間價相反
     * （那是 {@code @Transient} 衍生值，必須由 entity 算）。兩張表的欄位語意相同，在此正規化成同一組
     * {@code (date, o, h, l, c)} 後共用同一段寫表邏輯，確保切換指數時版面一致。
     *
     * <p><b>第六～九欄是本分頁唯一不直接取自 DB 欄位的四欄</b>（Task 284 建立 MA5、Task 285 補齊
     * MA20/60/240）：該日含當日往前 N 個交易日 {@code close} 的簡單移動平均（N ∈ {5,20,60,240}，
     * 交易日非日曆週／月／季／年）。定義與精度**必須**與本頁圖表的四條均線
     * （BFF {@code GdpTwseBffController.movingAverage(closes, window)}）逐位相同——同為 BigDecimal
     * 精確加總 ＋ {@code divide(window, 2, HALF_UP)}，故同一指數同一日期，畫面與檔案顯示同一個值。
     * 兩處是兩份實作（不同 Maven 專案、無法共用程式碼），取捨與被放棄的選項見
     * spec/design.md 的 Requirement 45「週線MA5：唯一的計算欄」。
     *
     * <p>{@code TWSE} 走 {@code twse_index_daily_history}、其餘走 {@code us_index_daily_history}；
     * 兩表的 open/high/low 皆 nullable（TWSE 早期由 v1.21.0 只抓 ClosingIndex 的殘留列），
     * null 該格留空、不補前值、不捏造（同油價金價／匯率）。日期寫成文字避免開啟端時區偏移一天。
     */
    private ExportDoc.Sheet indexDailySheet(String market,
                                            java.time.LocalDate start, java.time.LocalDate end) {
        List<String> headers = List.of("日期", "開盤", "最高", "最低", "收盤",
                "週線MA5", "月線MA20", "季線MA60", "年線MA240");
        List<ExportDoc.Format> formats = List.of(ExportDoc.Format.DATE, ExportDoc.Format.NUM4,
                ExportDoc.Format.NUM4, ExportDoc.Format.NUM4, ExportDoc.Format.NUM4,
                // 四條均線定義上只有 2 位小數（divide(window, 2, HALF_UP)）；
                // 用 NUM4 會多印兩個恆為 0 的位數而謊稱精度
                ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2);

        // 回看 MA_LOOKBACK_DAYS 天：只用區間內收盤時，檔案前幾列的均線必為空（使用者會讀成 bug）。
        // 回看列只參與均線計算，不得輸出。400 天由最長視窗 MA240（239 個交易日）決定，見常數 javadoc。
        List<IndexDailyRow> all = findIndexDaily(market, start.minusDays(MA_LOOKBACK_DAYS), end);
        List<List<Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            IndexDailyRow d = all.get(i);
            if (d.date().isBefore(start)) continue;   // 回看列：算完均線就丟
            rows.add(java.util.Arrays.asList(d.date(), d.open(), d.high(), d.low(), d.close(),
                    indexMaAt(all, i, 5), indexMaAt(all, i, 20), indexMaAt(all, i, 60), indexMaAt(all, i, 240)));
        }
        // omitNullCells=true：既有 open/high/low 是 if (x != null) cell(...)，缺值時該格根本不建。
        // close_point 為 NOT NULL（見上方 javadoc），故收盤那一欄不受此旗標影響。
        // 分頁名沿用 createSafeSheetName：indexLabel 可能含 POI 不允許的字元。
        return new ExportDoc.Sheet(
                org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(indexLabel(market)),
                List.of(new ExportDoc.Table(null, null, headers, true, false, true, formats, rows)),
                headers.size());
    }

    /** 兩張日線表正規化後的單日行情（僅供匯出寫表使用，不入庫）。 */
    private record IndexDailyRow(java.time.LocalDate date, BigDecimal open, BigDecimal high,
                                 BigDecimal low, BigDecimal close) {}

    /**
     * 均線回看天數（日曆天）。要湊滿最長視窗 MA240 需要當日之前的 239 個交易日；
     * 400 個日曆天約含 400÷7×5 ≈ 285 個平日，台股每年約 240～242 個交易日（年約 19～21 天非週末休市），
     * 折算約再扣 21～26 天休市日 ≈ 261～266 個交易日，對 239 仍有約 22～27 個交易日餘裕
     * （理論下限約 239×365/242 ≈ 361 個日曆天，400 尚有約 11% headroom；即使跨兩次農曆年的最壞情況，
     * 交易日仍約 259～260，≥ 239）。回看不足只會讓 {@link #indexMaAt} 回 null（缺值），不會算錯
     * ——故此常數選保守即可（Task 284 原為 30，只夠 MA5；Task 285 放大為 400 以支撐四條均線）。
     */
    private static final int MA_LOOKBACK_DAYS = 400;

    /**
     * {@code asc.get(i)} 那一天的均線：含當日往前 {@code window} 個交易日收盤的簡單移動平均
     * （{@code window ∈ {5, 20, 60, 240}}，對應週／月／季／年線）；視窗未滿回 {@code null}
     * （不補前值、不以不足視窗的平均充數）。
     *
     * <p><b>BigDecimal 精確加總、只在最後 {@code divide(window, 2, HALF_UP)} 捨入一次</b>——與 BFF
     * {@code GdpTwseBffController.movingAverage(closes, window)} 同定義同精度，兩處對同一組收盤逐位相同。
     * 不可改用 {@code double} 累加（加法不可結合，會在捨入邊界翻面，讓圖與檔案偶爾差 0.01）。
     *
     * <p>刻意不叫 {@code maAt}：{@code TechnicalIndicatorService.maAt} 是股票路徑的同名同形方法
     * （double 累加、可能併入 Redis 今日即時點位），語意不同，同名會讓
     * {@code grep -ran "maAt" backend} 混淆兩種實作（Task 285）。
     */
    private static BigDecimal indexMaAt(List<IndexDailyRow> asc, int i, int window) {
        if (i < window - 1) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = i - window + 1; j <= i; j++) {
            sum = sum.add(asc.get(j).close());
        }
        return sum.divide(BigDecimal.valueOf(window), 2, java.math.RoundingMode.HALF_UP);
    }

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
    private ExportDoc.Sheet liveAssetsSheet(StockPriceService.LiveAssetsResponse live, AssetSnapshot s) {
        List<ExportDoc.Block> blocks = new java.util.ArrayList<>();

        // r0：標題列（section 13pt）
        blocks.add(new ExportDoc.Line("當前即時資產", ExportDoc.LineStyle.SECTION_13));
        // r1：「匯出時間」＋值（label 走 head、值無樣式）
        blocks.add(new ExportDoc.KvRow(List.of(new ExportDoc.Kv(
                "匯出時間", LocalDateTime.now(TW_ZONE), ExportDoc.Format.TIMESTAMP))));

        if (s == null || live == null) {
            // 早退分支輸出三列（標題／匯出時間／「尚無資產快照」），autoSize 六欄。
            // 「尚無資產快照」既有是 cell(..., null)＝無樣式，故為 PLAIN，不可寫成 SECTION_13。
            blocks.add(new ExportDoc.Line("尚無資產快照", ExportDoc.LineStyle.PLAIN));
            return new ExportDoc.Sheet("當前即時資產", blocks, 6);
        }

        BigDecimal exchangeRate = live.exchangeRate();

        // r2：同一列六格的三組鍵值（拆成三個 Kv 會變三列、做成 Table 會變兩列，都是版面變動）
        blocks.add(new ExportDoc.KvRow(List.of(
                new ExportDoc.Kv("基準快照日期", s.getSnapshotDate(), ExportDoc.Format.DATE),
                new ExportDoc.Kv("美元匯率", exchangeRate, ExportDoc.Format.NUM4),
                new ExportDoc.Kv("即時總資產", live.liveTotalAssets(), ExportDoc.Format.MONEY))));
        blocks.add(new ExportDoc.Blank());

        // 即時彙總：既有 summaryRow 無表頭列，且第 0 欄標籤走 head 樣式
        blocks.add(new ExportDoc.Table("即時彙總", ExportDoc.LineStyle.SECTION_13,
                List.of("項目", "金額"), false, true, false,
                List.of(ExportDoc.Format.TEXT, ExportDoc.Format.MONEY),
                List.of(
                        java.util.Arrays.asList("存款總計", live.totalDeposit()),
                        java.util.Arrays.asList("基金現值", live.totalFundValue()),
                        java.util.Arrays.asList("即時股票現值", live.liveStockValue()),
                        java.util.Arrays.asList("即時總資產", live.liveTotalAssets()))));
        blocks.add(new ExportDoc.Blank());

        // 銀行存款（讀最新快照）
        List<List<Object>> depositRows = new java.util.ArrayList<>();
        for (BankDeposit d : s.getDeposits()) {
            depositRows.add(java.util.Arrays.asList(
                    d.getBank() != null ? d.getBank().getDisplayName() : "",   // 既有寫空字串，不可改 null
                    d.getDepositType(), d.getCurrency(),
                    d.getOriginalAmount(), d.getAmount(), d.getNotes()));
        }
        blocks.add(new ExportDoc.Table("銀行存款", ExportDoc.LineStyle.SECTION_13,
                List.of("銀行", "存款類型", "幣別", "原幣金額", "台幣金額", "備註"), true, false, false,
                List.of(ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                        ExportDoc.Format.MONEY, ExportDoc.Format.MONEY, ExportDoc.Format.TEXT),
                depositRows));
        blocks.add(new ExportDoc.Blank());

        // 基金（讀最新快照）
        List<List<Object>> fundRows = new java.util.ArrayList<>();
        for (FundHolding f : s.getFunds()) {
            fundRows.add(java.util.Arrays.asList(
                    f.getBank() != null ? f.getBank().getDisplayName() : "",   // 既有寫空字串
                    f.getFundName(), f.getInvestmentAmount(), f.getCurrentValue()));
        }
        blocks.add(new ExportDoc.Table("基金", ExportDoc.LineStyle.SECTION_13,
                List.of("銀行", "基金名稱", "投入成本", "現值"), true, false, false,
                List.of(ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                        ExportDoc.Format.MONEY, ExportDoc.Format.MONEY),
                fundRows));
        blocks.add(new ExportDoc.Blank());

        // 股票（即時價 + 即時現值）
        Map<String, StockPriceService.LiveStockItem> liveMap = new HashMap<>();
        for (StockPriceService.LiveStockItem it : live.stocks()) {
            liveMap.put(it.stockCode() + "|" + it.market(), it);
        }
        // 技術指標（月/季/年線、KD）逐 (code|market) 快取：同股多券商列僅算一次（Task 200）
        Map<String, TechnicalIndicatorService.FullIndicators> indicatorCache = new HashMap<>();
        // ETF 淨值／折溢價逐 (code|market) 快取（Task 214）；查無者快取 null，避免同檔多列重複讀 Redis
        Map<String, PriceQueryService.EtfNav> navCache = new HashMap<>();

        List<List<Object>> stockRows = new java.util.ArrayList<>();
        for (StockHolding sk : s.getStocks()) {
            StockPriceService.LiveStockItem it = liveMap.get(sk.getStockCode() + "|" + sk.getMarket());
            String stName = it != null ? it.stockName()
                    : stockMasterRepo.findByCodeAndMarket(sk.getStockCode(), sk.getMarket())
                        .map(Stock::getName).orElse(sk.getStockCode());
            BigDecimal livePrice = it != null ? it.currentPrice() : null;
            // 月/季/年線與 KD：共用權威 TechnicalIndicatorService（與觀察清單／警示同一計算），逐 code|market 快取
            TechnicalIndicatorService.FullIndicators ind = indicatorCache.computeIfAbsent(
                    sk.getStockCode() + "|" + sk.getMarket(),
                    k -> technicalIndicatorService.computeAll(sk.getStockCode(), sk.getMarket()));
            // ETF 淨值／折溢價（Task 214）：資料驅動——Redis 有值才印，個股（無淨值）與抓取失敗皆自然留白。
            // 用 containsKey 而非 computeIfAbsent：後者不會快取 null 值，個股（永遠查無）會逐列重讀 Redis
            String navKey = sk.getStockCode() + "|" + sk.getMarket();
            if (!navCache.containsKey(navKey)) {
                navCache.put(navKey, priceQueryService
                        .getEtfNav(sk.getStockCode(), sk.getMarket()).orElse(null));
            }
            PriceQueryService.EtfNav nav = navCache.get(navKey);

            stockRows.add(java.util.Arrays.asList(
                    sk.getBroker() != null ? sk.getBroker().getDisplayName() : "",   // 既有寫空字串
                    sk.getMarket(),
                    sk.getStockCode(),
                    stName,
                    sk.getShares(),
                    stockCostTwd(sk, exchangeRate),
                    livePrice,
                    // 昨收／漲跌／漲跌幅：與即時價同一筆 LivePrice（同一 tick），故「即時價 − 昨收 = 漲跌」自洽
                    it != null ? it.previousClose() : null,
                    it != null ? it.priceChange() : null,
                    it != null ? it.changePercent() : null,
                    // 即時現值 per-holding：即時價 × 該列股數（美股/英股再 × 匯率），比照 getLiveAssets 每檔算法。
                    // 不可直接取 LiveStockItem.liveValue：同一 code|market 多筆持股在 liveMap 會互相覆蓋。
                    liveStockValueTwd(sk, livePrice, exchangeRate),
                    sk.getEstimatedDividend(),
                    sk.getTransactionType(),
                    sk.getTransactionDate() != null ? ISO.format(sk.getTransactionDate()) : "",  // 既有寫空字串
                    ind.monthlyMa(),
                    ind.quarterlyMa(),
                    ind.annualMa(),
                    formatKd(ind.k(), ind.d()),
                    nav == null ? null : nav.nav(),
                    premiumDiscountPct(nav, livePrice),
                    nav == null ? null : nav.navAsOf()));
        }
        blocks.add(new ExportDoc.Table("股票（即時）", ExportDoc.LineStyle.SECTION_13,
                List.of("券商", "市場", "代號", "名稱", "股數", "投資成本", "即時價", "昨收", "漲跌", "漲跌幅(%)",
                        "即時現值", "預估配息", "交易類型", "交易日期", "月線價", "季線價", "年線價", "KD值",
                        // ETF 淨值／折溢價（Task 214）：個股無淨值故留白
                        "淨值", "折溢價(%)", "淨值時間"),
                true, false, false,
                List.of(ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                        ExportDoc.Format.TEXT, ExportDoc.Format.NUM4, ExportDoc.Format.MONEY,
                        ExportDoc.Format.NUM4, ExportDoc.Format.NUM4, ExportDoc.Format.NUM2,
                        ExportDoc.Format.NUM2, ExportDoc.Format.MONEY, ExportDoc.Format.MONEY,
                        ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2,
                        ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT,
                        ExportDoc.Format.NUM4, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT),
                stockRows));

        return new ExportDoc.Sheet("當前即時資產", blocks, 21);
    }

    /**
     * 每檔持股一張「過去一年股價」分頁（Task 206）：分頁名＝股票代號，接在「當前即時資產」總表之後。
     *
     * <p>持股清單取自與總表<b>同一個</b> {@code s}（同交易 lazy load，不另查），以 {@code (code|market)} 去重
     * ——同一檔分散多家券商只出一張分頁——並保留總表由上而下的順序。{@code s} 為 null（尚無快照）時不產生分頁。
     * owner 隔離沿用外層（HTTP 走 aspect、背景排程走手動 {@code enableFilter}），故只含使用者自己持有的股票。
     */
    private List<ExportDoc.Sheet> stockPriceHistorySheets(AssetSnapshot s) {
        if (s == null) return List.of();
        java.time.LocalDate end = java.time.LocalDate.now(TW_ZONE);
        java.time.LocalDate start = end.minusYears(1);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        // 分頁名去重：既有靠 wb.getSheet() 查，改建 doc 後沒有 workbook 可問，故自備一個
        // 大小寫不敏感的 Set（對齊 POI getSheet() 的 equalsIgnoreCase 語意），並先放入已存在的分頁名。
        java.util.Set<String> usedNames = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        usedNames.add("當前即時資產");
        List<ExportDoc.Sheet> sheets = new java.util.ArrayList<>();
        for (StockHolding sk : s.getStocks()) {
            String code = sk.getStockCode();
            if (code == null || code.isBlank()) continue;
            if (!seen.add(code + "|" + sk.getMarket())) continue; // 同檔多券商只出一張
            sheets.add(stockPriceHistorySheet(uniqueStockSheetName(usedNames, code, sk.getMarket()),
                    code, sk.getMarket(), start, end));
        }
        return sheets;
    }

    /**
     * 單一個股的「過去一年股價」分頁：表頭 ＋ 逐交易日一列（日期遞增），資料源 {@code stock_price_history}
     * （收盤價唯一權威來源，與 {@link TechnicalIndicatorService} 的 MA／KD 同源，匯出過程零外部行情呼叫）。
     *
     * <p>日期寫成文字（同油價金價／匯率分頁：避免 Excel 依開啟端時區重新詮釋 date cell 而偏移一天）。
     * 開高低與成交量在 DB 可空（僅 {@code close_price} NOT NULL），該格留白不補值。
     * <b>區間內查無資料仍建立只有表頭的空分頁</b>，讓「持有但無資料」與「未持有」可區分，不靜默略過。
     */
    private ExportDoc.Sheet stockPriceHistorySheet(String sheetName, String code, String market,
                                                  java.time.LocalDate start, java.time.LocalDate end) {
        List<List<Object>> rows = new java.util.ArrayList<>();
        for (StockPriceHistory p : priceHistRepo
                .findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(code, market, start, end)) {
            rows.add(java.util.Arrays.asList(
                    p.getTradingDate(), p.getOpenPrice(), p.getHighPrice(),
                    p.getLowPrice(), p.getClosePrice(), p.getVolume()));
        }
        // omitNullCells=false：六欄逐格都是無條件 cell(...)，null 產生 BLANK 格
        //（既有 javadoc 的「該格留白」指的是 BLANK 格，不是「不建格」）
        return new ExportDoc.Sheet(sheetName,
                List.of(new ExportDoc.Table(null, null,
                        List.of("日期", "開盤價", "最高價", "最低價", "收盤價", "成交量"),
                        true, false, false,
                        List.of(ExportDoc.Format.DATE, ExportDoc.Format.NUM4, ExportDoc.Format.NUM4,
                                ExportDoc.Format.NUM4, ExportDoc.Format.NUM4, ExportDoc.Format.TEXT),
                        rows)),
                6);
    }

    /**
     * 股價分頁名稱：首選股票代號，經 {@code createSafeSheetName} 收斂 Excel 限制（31 字元上限、禁 {@code []:*?/\}）。
     * 不同市場出現同一代號時改 {@code 代號_市場}；仍衝突再加數字後綴（比照 {@link #writeSnapshotSheet}）。
     */
    private static String uniqueStockSheetName(java.util.Set<String> used, String code, String market) {
        String base = org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(code);
        if (used.add(base)) return base;
        String withMarket = org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(code + "_" + market);
        if (used.add(withMarket)) return withMarket;
        int suffix = 1;
        String unique;
        do {
            unique = org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(withMarket + "_" + (++suffix));
        } while (!used.add(unique));
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

    /**
     * 「已實現損益」分頁的 {@link ExportDoc.Sheet}（Requirement 55 / Task 270）。
     *
     * <p><b>{@code omitNullCells = false}</b>：本分頁逐格都無條件呼叫寫格，null 產生 BLANK 格
     * （與油價金價／匯率／指數那三份「值為 null 就不建格」不同）。
     *
     * <p><b>「交易日期」為 null 時寫空字串 {@code ""} 而非 null</b>——這是既有行為，改成 null 會讓
     * 儲存格由「空字串格」變 BLANK 格，是可觀測的版面變動（Requirement 55 已登錄的具名例外二）。
     */
    private ExportDoc.Sheet realizedGainsSheet() {
        List<String> headers = List.of("資產名稱","代號","交易日期","股數","賣出均價","收帳金額","投資成本",
                "損益","報酬率","市場","幣別","券商","匯率","年度");
        List<ExportDoc.Format> formats = List.of(
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                ExportDoc.Format.NUM4, ExportDoc.Format.NUM4, ExportDoc.Format.MONEY,
                ExportDoc.Format.MONEY, ExportDoc.Format.MONEY, ExportDoc.Format.NUM4,
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                ExportDoc.Format.NUM4, ExportDoc.Format.TEXT);

        List<List<Object>> rows = new java.util.ArrayList<>();
        for (RealizedGain g : gainRepo.findAllByOrderByTradeDateDesc()) {
            BigDecimal profit = g.getProceeds().subtract(g.getInvestmentCost());
            BigDecimal profitRate = g.getInvestmentCost().compareTo(BigDecimal.ZERO) != 0
                    ? profit.divide(g.getInvestmentCost(), 6, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            rows.add(java.util.Arrays.asList(
                    g.getAssetName(),
                    g.getAssetCode(),
                    g.getTradeDate() != null ? ISO.format(g.getTradeDate()) : "",
                    g.getShares(),
                    g.getSalePrice(),
                    g.getProceeds(),
                    g.getInvestmentCost(),
                    profit,
                    profitRate,
                    g.getMarket(),
                    g.getCurrency(),
                    g.getBroker(),
                    g.getExchangeRate(),
                    g.getYear()));
        }
        return new ExportDoc.Sheet("已實現損益",
                List.of(new ExportDoc.Table(null, null, headers, true, false, false, formats, rows)),
                headers.size());
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
