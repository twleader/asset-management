package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.service.ExcelExportService;
import com.steven.assets.service.PriceQueryService;
import com.steven.assets.service.StockPriceService;
import com.steven.assets.service.TechnicalIndicatorService;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「當前即時資產」分頁改走 {@link ExportDoc} 之後的零回歸與雙格式驗證（Requirement 55 / Task 271）。
 *
 * <p>這張分頁是十個匯出點裡版面最複雜的：同一列六格的鍵值、無表頭的彙總列、早退分支三列、
 * 四處不得多插的空行。基準為 origin/main 以相同 fixture 產出的 golden，逐列逐格比對。
 *
 * <p><b>「匯出時間」是牆鐘值</b>（Requirement 55 具名例外三），比對時只驗存在與型別、不比值。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiveAssetsDualFormatTest {

    @Mock private AssetSnapshotRepository snapshotRepo;
    @Mock private RealizedGainRepository gainRepo;
    @Mock private AssetTransactionRepository assetTxRepo;
    @Mock private StockRepository stockMasterRepo;
    @Mock private StockPriceService stockPriceService;
    @Mock private TechnicalIndicatorService technicalIndicatorService;
    @Mock private CommodityPriceHistoryRepository commodityHistRepo;
    @Mock private ExchangeRateHistoryRepository rateHistRepo;
    @Mock private StockPriceHistoryRepository priceHistRepo;
    @Mock private PriceQueryService priceQueryService;
    @Mock private TwseIndexDailyHistoryRepository twseIndexHistRepo;
    @Mock private UsIndexDailyHistoryRepository usIndexHistRepo;

    @Spy private ExcelDocRenderer excelDocRenderer = new ExcelDocRenderer();
    @InjectMocks private ExcelExportService service;

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonDocRenderer jsonRenderer = new JsonDocRenderer(mapper);

    private static final LocalDate SNAP = LocalDate.of(2026, 7, 31);
    /** 「匯出時間」那一格：分頁「當前即時資產」的第 1 列第 1 欄，是牆鐘值。 */
    private static final Set<String> WALL_CLOCK = Set.of("當前即時資產:1:1");

    private void stubFull() {
        when(stockPriceService.getLiveAssets()).thenReturn(live());
        when(snapshotRepo.findLatest()).thenReturn(Optional.of(snapshot()));
        when(technicalIndicatorService.computeAll(anyString(), anyString())).thenReturn(indicators());
        when(priceQueryService.getEtfNav(anyString(), anyString())).thenAnswer(inv ->
                "0050".equals(inv.getArgument(0)) ? Optional.of(nav()) : Optional.empty());
        when(stockMasterRepo.findByCodeAndMarket(anyString(), anyString())).thenReturn(Optional.empty());
        when(priceHistRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                anyString(), anyString(), any(), any())).thenReturn(priceHistory());
    }

    // ===== 零回歸 =====

    @Test
    @DisplayName("有資料時逐列逐格與 origin/main 相同（含四張分頁、四處空行、同列六格）")
    void 零回歸_有資料() throws Exception {
        stubFull();
        Workbook actual = GoldenWorkbooks.read(service.exportLiveAssets());
        GoldenWorkbooks.assertSame(GoldenWorkbooks.golden("live_assets"), actual, WALL_CLOCK);
    }

    @Test
    @DisplayName("早退分支（無快照）逐列逐格與 origin/main 相同：三列，且「尚無資產快照」無樣式")
    void 零回歸_早退分支() throws Exception {
        when(stockPriceService.getLiveAssets()).thenReturn(null);
        when(snapshotRepo.findLatest()).thenReturn(Optional.empty());

        Workbook actual = GoldenWorkbooks.read(service.exportLiveAssets());
        GoldenWorkbooks.assertSame(GoldenWorkbooks.golden("live_assets_empty"), actual, WALL_CLOCK);

        Sheet s = actual.getSheetAt(0);
        assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("當前即時資產");
        assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("匯出時間");
        assertThat(s.getRow(2).getCell(0).getStringCellValue()).isEqualTo("尚無資產快照");
        assertThat(s.getRow(2).getCell(0).getCellStyle().getIndex())
                .as("既有是 cell(..., null)＝無樣式，不可寫成 SECTION_13").isEqualTo((short) 0);
        assertThat(s.getRow(3)).as("早退後不得再有列").isNull();
    }

    // ===== 版面關鍵點 =====

    @Test
    @DisplayName("同一列六格的鍵值：r2 是單列六格，JSON 中三者併進同一個 meta")
    void 同列六格() throws Exception {
        stubFull();
        Row r2 = GoldenWorkbooks.read(service.exportLiveAssets()).getSheetAt(0).getRow(2);
        assertThat(r2.getLastCellNum()).as("三組鍵值佔同一列六格").isEqualTo((short) 6);
        assertThat(r2.getCell(0).getStringCellValue()).isEqualTo("基準快照日期");
        assertThat(r2.getCell(1).getStringCellValue()).isEqualTo("2026-07-31");
        assertThat(r2.getCell(2).getStringCellValue()).isEqualTo("美元匯率");
        assertThat(r2.getCell(4).getStringCellValue()).isEqualTo("即時總資產");

        JsonNode meta = mapper.readTree(jsonRenderer.render(service.liveAssetsDoc())).at("/sheets/0/meta");
        assertThat(meta.has("匯出時間")).isTrue();
        assertThat(meta.has("基準快照日期")).isTrue();
        assertThat(meta.get("美元匯率").isNumber()).isTrue();
    }

    @Test
    @DisplayName("即時彙總：section 標題列的下一列直接是資料列（無表頭），且第 0 欄走 head 樣式")
    void 即時彙總無表頭() throws Exception {
        stubFull();
        Sheet s = GoldenWorkbooks.read(service.exportLiveAssets()).getSheetAt(0);
        assertThat(s.getRow(3)).as("r3 是空行、不得建 Row").isNull();
        assertThat(s.getRow(4).getCell(0).getStringCellValue()).isEqualTo("即時彙總");
        // r5 直接是第一筆資料，不是表頭列
        assertThat(s.getRow(5).getCell(0).getStringCellValue()).isEqualTo("存款總計");
        assertThat(s.getRow(5).getCell(0).getCellStyle().getIndex())
                .as("標籤欄走 head 樣式").isNotEqualTo((short) 0);
        assertThat(s.getRow(5).getCell(1).getNumericCellValue()).isEqualTo(500000.0);
        // JSON 側仍以 headers 當 key
        JsonNode rows = mapper.readTree(jsonRenderer.render(service.liveAssetsDoc()))
                .at("/sheets/0/tables/0/rows");
        assertThat(rows.get(0).get("項目").asText()).isEqualTo("存款總計");
        assertThat(rows.get(0).get("金額").isNumber()).isTrue();
    }

    // ===== 快取與去重 =====

    @Test
    @DisplayName("同一檔被兩個券商持有時，技術指標與 ETF 淨值各只查一次")
    void 兩個快取都保留() throws Exception {
        stubFull();
        service.exportLiveAssets();
        verify(technicalIndicatorService, times(1)).computeAll("2330", "TW");
        verify(technicalIndicatorService, times(1)).computeAll("0050", "TW");
        // 查無淨值的個股（2330）在多列情況下仍只讀一次——containsKey vs computeIfAbsent 的探針
        verify(priceQueryService, times(1)).getEtfNav("2330", "TW");
        verify(priceQueryService, times(1)).getEtfNav("0050", "TW");
    }

    @Test
    @DisplayName("持股層級去重：同代號同市場分屬兩券商只出一張分頁；空代號跳過")
    void 持股層級去重() throws Exception {
        AssetSnapshot s = snapshot();
        StockHolding blank = new StockHolding();
        blank.setMarket("TW"); blank.setStockCode("  ");   // 空白代號必須被跳過
        blank.setShares(BigDecimal.ONE); blank.setCurrency("TWD");
        s.setStocks(List.of(s.getStocks().get(0), s.getStocks().get(1), s.getStocks().get(2), blank));
        when(stockPriceService.getLiveAssets()).thenReturn(live());
        when(snapshotRepo.findLatest()).thenReturn(Optional.of(s));
        when(technicalIndicatorService.computeAll(anyString(), anyString())).thenReturn(indicators());
        when(priceQueryService.getEtfNav(anyString(), anyString())).thenReturn(Optional.empty());
        when(stockMasterRepo.findByCodeAndMarket(anyString(), anyString())).thenReturn(Optional.empty());
        when(priceHistRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                anyString(), anyString(), any(), any())).thenReturn(priceHistory());

        Workbook wb = GoldenWorkbooks.read(service.exportLiveAssets());
        // 總表 ＋ 2330 ＋ 0050＝三張（2330 兩筆持股只出一張、空白代號不出）
        assertThat(wb.getNumberOfSheets()).isEqualTo(3);
        assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("當前即時資產");
        assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("2330");
        assertThat(wb.getSheetAt(2).getSheetName()).isEqualTo("0050");
    }

    @Test
    @DisplayName("分頁名與總表撞名時自動避開（去重容器須先放入已存在的分頁名）")
    void 分頁名不與總表撞名() throws Exception {
        AssetSnapshot s = snapshot();
        StockHolding clash = new StockHolding();
        clash.setMarket("TW"); clash.setStockCode("當前即時資產");
        clash.setShares(BigDecimal.ONE); clash.setCurrency("TWD");
        s.setStocks(List.of(clash));
        when(stockPriceService.getLiveAssets()).thenReturn(live());
        when(snapshotRepo.findLatest()).thenReturn(Optional.of(s));
        when(technicalIndicatorService.computeAll(anyString(), anyString())).thenReturn(indicators());
        when(priceQueryService.getEtfNav(anyString(), anyString())).thenReturn(Optional.empty());
        when(stockMasterRepo.findByCodeAndMarket(anyString(), anyString())).thenReturn(Optional.empty());
        when(priceHistRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                anyString(), anyString(), any(), any())).thenReturn(List.of());

        Workbook wb = GoldenWorkbooks.read(service.exportLiveAssets());
        assertThat(wb.getNumberOfSheets()).isEqualTo(2);
        assertThat(wb.getSheetAt(1).getSheetName())
                .as("撞名時退到 代號_市場").isEqualTo("當前即時資產_TW");
    }

    // ===== JSON 語意 =====

    @Test
    @DisplayName("JSON：數值為 number、缺值為 null，既有寫空字串的欄位維持空字串（具名例外二）")
    void json語意() throws Exception {
        stubFull();
        JsonNode doc = mapper.readTree(jsonRenderer.render(service.liveAssetsDoc()));
        JsonNode stockRows = doc.at("/sheets/0/tables/3/rows");
        assertThat(stockRows.get(0).get("即時價").isNumber()).isTrue();
        // 第二筆是無券商的 2330：券商欄既有寫空字串
        assertThat(stockRows.get(1).get("券商").asText()).isEmpty();
        assertThat(stockRows.get(1).get("交易日期").asText()).isEmpty();
        // 0050 有淨值、2330 查無 → null
        assertThat(stockRows.get(0).get("淨值").isNull()).isTrue();
        assertThat(stockRows.get(2).get("淨值").isNumber()).isTrue();
        // 存款第二筆金額為 null
        assertThat(doc.at("/sheets/0/tables/1/rows/1/台幣金額").isNull()).isTrue();
        assertThat(doc.at("/sheets/0/tables/1/rows/1/銀行").asText()).isEmpty();
    }

    @Test
    @DisplayName("個股股價分頁：無值欄位是 BLANK 格（不是不建格），JSON 為 null")
    void 個股股價分頁的null語意() throws Exception {
        stubFull();
        Sheet s = GoldenWorkbooks.read(service.exportLiveAssets()).getSheetAt(1);
        Row r2 = s.getRow(2);   // 第二筆：開高低與成交量皆 null
        assertThat(r2.getCell(1)).as("既有無條件寫格 → BLANK 格存在").isNotNull();
        assertThat(r2.getCell(1).getCellType()).isEqualTo(CellType.BLANK);
        assertThat(r2.getLastCellNum()).isEqualTo((short) 6);

        JsonNode rows = mapper.readTree(jsonRenderer.render(service.liveAssetsDoc()))
                .at("/sheets/1/tables/0/rows");
        assertThat(rows.get(1).get("開盤價").isNull()).isTrue();
    }

    // ===== fixture（與 golden 產生器逐字相同）=====

    private static StockPriceService.LiveAssetsResponse live() {
        var a = new StockPriceService.LiveStockItem("2330", "台積電", "TW",
                new BigDecimal("1000"), new BigDecimal("1105.0000"), new BigDecimal("1105000"),
                Boolean.FALSE, "2026-07-31",
                new BigDecimal("1100.0000"), new BigDecimal("5.00"), new BigDecimal("0.45"));
        var b = new StockPriceService.LiveStockItem("0050", "元大台灣50", "TW",
                new BigDecimal("2000"), new BigDecimal("195.5000"), new BigDecimal("391000"),
                Boolean.FALSE, "2026-07-31", null, null, null);
        return new StockPriceService.LiveAssetsResponse(
                1L, "2026-07-31", new BigDecimal("32.1054"),
                new BigDecimal("500000"), new BigDecimal("300000"),
                new BigDecimal("1496000"), new BigDecimal("2296000"),
                List.of(a, b), true, false, false, "2026-07-31 13:30:00");
    }

    private static AssetSnapshot snapshot() {
        AssetSnapshot s = new AssetSnapshot();
        s.setSnapshotDate(SNAP);
        s.setUsdExchangeRate(new BigDecimal("32.1054"));

        Bank bank = new Bank();
        bank.setDisplayName("國泰世華");
        BankDeposit d1 = new BankDeposit();
        d1.setBank(bank); d1.setDepositType("定存"); d1.setCurrency("TWD");
        d1.setOriginalAmount(new BigDecimal("500000")); d1.setAmount(new BigDecimal("500000"));
        d1.setNotes("一年期");
        BankDeposit d2 = new BankDeposit();
        d2.setBank(null); d2.setDepositType("活存"); d2.setCurrency("USD");
        d2.setOriginalAmount(null); d2.setAmount(null); d2.setNotes(null);
        s.setDeposits(List.of(d1, d2));

        FundHolding f1 = new FundHolding();
        f1.setBank(bank); f1.setFundName("全球債券");
        f1.setInvestmentAmount(new BigDecimal("250000")); f1.setCurrentValue(new BigDecimal("300000"));
        FundHolding f2 = new FundHolding();
        f2.setBank(null); f2.setFundName(null);
        f2.setInvestmentAmount(null); f2.setCurrentValue(null);
        s.setFunds(List.of(f1, f2));

        BrokerEntity broker = new BrokerEntity();
        broker.setDisplayName("元大");
        StockHolding k1 = new StockHolding();
        k1.setBroker(broker); k1.setMarket("TW"); k1.setStockCode("2330");
        k1.setShares(new BigDecimal("1000")); k1.setInvestmentCost(new BigDecimal("1000000"));
        k1.setCurrentValue(new BigDecimal("1105000")); k1.setEstimatedDividend(new BigDecimal("15000"));
        k1.setTransactionType("BUY"); k1.setTransactionDate(LocalDate.of(2026, 1, 5));
        k1.setCurrency("TWD");
        StockHolding k2 = new StockHolding();
        k2.setBroker(null); k2.setMarket("TW"); k2.setStockCode("2330");
        k2.setShares(new BigDecimal("500")); k2.setInvestmentCost(new BigDecimal("500000"));
        k2.setCurrentValue(new BigDecimal("552500")); k2.setEstimatedDividend(null);
        k2.setTransactionType(null); k2.setTransactionDate(null);
        k2.setCurrency("TWD");
        StockHolding k3 = new StockHolding();
        k3.setBroker(broker); k3.setMarket("TW"); k3.setStockCode("0050");
        k3.setShares(new BigDecimal("2000")); k3.setInvestmentCost(new BigDecimal("380000"));
        k3.setCurrentValue(new BigDecimal("391000")); k3.setEstimatedDividend(new BigDecimal("8000"));
        k3.setTransactionType("BUY"); k3.setTransactionDate(LocalDate.of(2026, 3, 10));
        k3.setCurrency("TWD");
        s.setStocks(List.of(k1, k2, k3));
        return s;
    }

    private static TechnicalIndicatorService.FullIndicators indicators() {
        // 第 8 個是 weeklyMa（Task 265 新增）：傳 null，比照
        // StockAlertTriggerExportService.buildCondition——「當前即時資產」分頁不讀週線，產出不變。
        return new TechnicalIndicatorService.FullIndicators(
                new BigDecimal("1080.00"), new BigDecimal("1050.00"), new BigDecimal("990.00"),
                new BigDecimal("75.12"), new BigDecimal("68.34"), null, null, null, TechnicalIndicatorService.ExtendedIndicators.EMPTY);
    }

    private static PriceQueryService.EtfNav nav() {
        return new PriceQueryService.EtfNav("0050", "TW", new BigDecimal("194.8000"),
                null, "2026-07-31", "sitca");
    }

    private static List<StockPriceHistory> priceHistory() {
        StockPriceHistory p = new StockPriceHistory();
        p.setStockCode("2330"); p.setMarket("TW"); p.setTradingDate(SNAP);
        p.setOpenPrice(new BigDecimal("1100.0000")); p.setHighPrice(new BigDecimal("1110.0000"));
        p.setLowPrice(new BigDecimal("1095.0000")); p.setClosePrice(new BigDecimal("1105.0000"));
        p.setVolume(12345L);
        StockPriceHistory q = new StockPriceHistory();
        q.setStockCode("2330"); q.setMarket("TW"); q.setTradingDate(SNAP.minusDays(1));
        q.setOpenPrice(null); q.setHighPrice(null); q.setLowPrice(null);
        q.setClosePrice(new BigDecimal("1100.0000")); q.setVolume(null);
        return List.of(p, q);
    }
}
