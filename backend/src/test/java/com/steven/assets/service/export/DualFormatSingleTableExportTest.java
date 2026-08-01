package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.CommodityPriceHistory;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.RealizedGain;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.*;
import com.steven.assets.service.ExcelExportService;
import com.steven.assets.service.PriceQueryService;
import com.steven.assets.service.StockPriceService;
import com.steven.assets.service.TechnicalIndicatorService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 五個單表匯出改走 {@link ExportDoc} 之後的零回歸與雙格式驗證（Requirement 55 / Task 270）。
 *
 * <p><b>零回歸的基準是 golden file</b>（{@code src/test/resources/golden/*.xlsx}），由 origin/main
 * 的臨時 worktree 以完全相同的 fixture 產出。單元測試無法編譯執行舊版 service，故不可能「在測試裡跑舊版」；
 * 拿改動後的程式碼算期望值則是「程式碼等於它自己」的假綠燈。
 *
 * <p>比對逐列逐格：每格的型別、值、以及 {@code CellStyle} 的 {@code dataFormat} 與字型，
 * 外加 {@code getLastCellNum()}——後者是「BLANK 格 vs 完全不建格」唯一分辨得出來的地方。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DualFormatSingleTableExportTest {

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

    /** 真實實例：本測試要驗真的產出，換成 mock 就什麼都驗不到。 */
    @Spy private ExcelDocRenderer excelDocRenderer = new ExcelDocRenderer();

    @InjectMocks private ExcelExportService service;

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonDocRenderer jsonRenderer = new JsonDocRenderer(mapper);

    private static final LocalDate D1 = LocalDate.of(2026, 7, 30);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 31);

    // ===== 輔助 =====

    private static Workbook golden(String name) throws Exception {
        try (InputStream in = DualFormatSingleTableExportTest.class
                .getResourceAsStream("/golden/" + name + ".xlsx")) {
            assertThat(in).as("golden file /golden/%s.xlsx 必須存在", name).isNotNull();
            return new XSSFWorkbook(in);
        }
    }

    /** 逐列逐格比對兩份 workbook（含型別、值、dataFormat、字型、lastCellNum、分頁名）。 */
    private static void assertSameWorkbook(Workbook expected, Workbook actual) {
        assertThat(actual.getNumberOfSheets()).isEqualTo(expected.getNumberOfSheets());
        for (int si = 0; si < expected.getNumberOfSheets(); si++) {
            Sheet e = expected.getSheetAt(si);
            Sheet a = actual.getSheetAt(si);
            assertThat(a.getSheetName()).as("分頁名").isEqualTo(e.getSheetName());
            assertThat(a.getLastRowNum()).as("%s 列數", e.getSheetName()).isEqualTo(e.getLastRowNum());
            for (int ri = 0; ri <= e.getLastRowNum(); ri++) {
                Row er = e.getRow(ri);
                Row ar = a.getRow(ri);
                if (er == null) {
                    assertThat(ar).as("%s 第 %d 列應不存在（空行不得建 Row）", e.getSheetName(), ri).isNull();
                    continue;
                }
                assertThat(ar).as("%s 第 %d 列", e.getSheetName(), ri).isNotNull();
                assertThat(ar.getLastCellNum())
                        .as("%s 第 %d 列的格數（BLANK 格 vs 不建格的唯一分辨點）", e.getSheetName(), ri)
                        .isEqualTo(er.getLastCellNum());
                for (int ci = 0; ci < er.getLastCellNum(); ci++) {
                    Cell ec = er.getCell(ci);
                    Cell ac = ar.getCell(ci);
                    String where = String.format("%s (%d,%d)", e.getSheetName(), ri, ci);
                    if (ec == null) {
                        assertThat(ac).as("%s 該格應不存在", where).isNull();
                        continue;
                    }
                    assertThat(ac).as("%s 該格應存在", where).isNotNull();
                    assertThat(ac.getCellType()).as("%s 型別", where).isEqualTo(ec.getCellType());
                    if (ec.getCellType() == CellType.STRING) {
                        assertThat(ac.getStringCellValue()).as("%s 值", where).isEqualTo(ec.getStringCellValue());
                    } else if (ec.getCellType() == CellType.NUMERIC) {
                        assertThat(ac.getNumericCellValue()).as("%s 值", where).isEqualTo(ec.getNumericCellValue());
                    }
                    assertThat(ac.getCellStyle().getDataFormatString())
                            .as("%s dataFormat", where).isEqualTo(ec.getCellStyle().getDataFormatString());
                    var ef = expected.getFontAt(ec.getCellStyle().getFontIndex());
                    var af = actual.getFontAt(ac.getCellStyle().getFontIndex());
                    assertThat(af.getBold()).as("%s 粗體", where).isEqualTo(ef.getBold());
                    assertThat(af.getFontHeightInPoints()).as("%s 字級", where).isEqualTo(ef.getFontHeightInPoints());
                }
            }
        }
    }

    private static Workbook read(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private JsonNode json(ExportDoc doc) throws Exception {
        return mapper.readTree(jsonRenderer.render(doc));
    }

    // ===== fixture（與 golden 產生器逐字相同）=====

    private static List<RealizedGain> gains() {
        RealizedGain a = new RealizedGain();
        a.setAssetName("台積電"); a.setAssetCode("2330"); a.setTradeDate(D1);
        a.setShares(new BigDecimal("1000")); a.setSalePrice(new BigDecimal("1105.5"));
        a.setProceeds(new BigDecimal("1105500")); a.setInvestmentCost(new BigDecimal("1000000"));
        a.setMarket("TW"); a.setCurrency("TWD"); a.setBroker("元大");
        a.setExchangeRate(new BigDecimal("1.0000"));
        RealizedGain b = new RealizedGain();
        b.setAssetName("無日期"); b.setAssetCode("NULLDATE"); b.setTradeDate(null);
        b.setShares(new BigDecimal("1")); b.setSalePrice(new BigDecimal("1"));
        b.setProceeds(new BigDecimal("1")); b.setInvestmentCost(new BigDecimal("0"));
        b.setMarket("US"); b.setCurrency("USD"); b.setBroker(null);
        b.setExchangeRate(null);
        return List.of(a, b);
    }

    private static List<AssetTransaction> txs() {
        AssetTransaction a = new AssetTransaction();
        a.setAssetName("台積電"); a.setAssetCode("2330"); a.setTransactionType("BUY");
        a.setAssetType("STOCK"); a.setTradeDate(D1); a.setShares(new BigDecimal("1000"));
        a.setPrice(new BigDecimal("1105.500000")); a.setAmount(new BigDecimal("1105500"));
        // Task 268 的兩欄：第一筆有值（驗 MONEY 格式），第二筆刻意留 null（驗無樣式 BLANK 格）。
        // fee 的 null≠0 是刻意的語意，JSON 側必須維持 null 才分辨得出「沒記費用」與「確實免收」。
        a.setFee(new BigDecimal("1575")); a.setTransactionTax(new BigDecimal("3316"));
        a.setMarket("TW"); a.setCurrency("TWD"); a.setChannel("元大");
        a.setExchangeRate(null); a.setNotes("備註");
        AssetTransaction b = new AssetTransaction();
        b.setAssetName("蘋果"); b.setAssetCode("AAPL"); b.setTransactionType("SELL");
        b.setAssetType("STOCK"); b.setTradeDate(null); b.setShares(new BigDecimal("10"));
        b.setPrice(new BigDecimal("200.000000")); b.setAmount(new BigDecimal("2000"));
        b.setMarket("US"); b.setCurrency("USD"); b.setChannel(null);
        b.setExchangeRate(new BigDecimal("32.1054")); b.setNotes(null);
        return List.of(a, b);
    }

    private static List<CommodityPriceHistory> commodities(String code) {
        if ("WTI".equals(code)) {
            CommodityPriceHistory p = new CommodityPriceHistory();
            p.setCommodityCode("WTI"); p.setPriceDate(D1); p.setClosePrice(new BigDecimal("70.1234"));
            return List.of(p);
        }
        if ("GOLD".equals(code)) {
            CommodityPriceHistory p = new CommodityPriceHistory();
            p.setCommodityCode("GOLD"); p.setPriceDate(D2); p.setClosePrice(new BigDecimal("2400.5678"));
            return List.of(p);
        }
        return List.of();
    }

    private static List<ExchangeRateHistory> rates() {
        ExchangeRateHistory a = new ExchangeRateHistory();
        a.setCurrency("USD"); a.setRateDate(D1);
        a.setBuyRate(new BigDecimal("32.0000")); a.setSellRate(new BigDecimal("32.2000"));
        ExchangeRateHistory b = new ExchangeRateHistory();
        b.setCurrency("USD"); b.setRateDate(D2);
        b.setBuyRate(null); b.setSellRate(null);
        return List.of(a, b);
    }

    private static List<TwseIndexDailyHistory> twseIndex() {
        TwseIndexDailyHistory a = new TwseIndexDailyHistory();
        a.setTradingDate(D1); a.setOpenPoint(new BigDecimal("23000.00"));
        a.setHighPoint(new BigDecimal("23100.00")); a.setLowPoint(new BigDecimal("22900.00"));
        a.setClosePoint(new BigDecimal("23050.00"));
        TwseIndexDailyHistory b = new TwseIndexDailyHistory();
        b.setTradingDate(D2); b.setOpenPoint(null); b.setHighPoint(null); b.setLowPoint(null);
        b.setClosePoint(new BigDecimal("23200.00"));
        return List.of(a, b);
    }

    private void stubAll() {
        when(gainRepo.findAllByOrderByTradeDateDesc()).thenReturn(gains());
        when(assetTxRepo.findAllByOrderByTradeDateDesc()).thenReturn(txs());
        when(commodityHistRepo.findByCommodityCodeAndPriceDateBetweenOrderByPriceDateAsc(anyString(), any(), any()))
                .thenAnswer(inv -> commodities(inv.getArgument(0)));
        when(rateHistRepo.findByCurrencyAndRateDateBetweenOrderByRateDateAsc(anyString(), any(), any()))
                .thenReturn(rates());
        when(twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc(any(), any()))
                .thenReturn(twseIndex());
    }

    // ===== 測試 =====

    @Nested
    @DisplayName("既有 Excel 逐列逐格零回歸（基準＝origin/main 產出的 golden file）")
    class ZeroRegression {

        @Test
        void 已實現損益() throws Exception {
            stubAll();
            assertSameWorkbook(golden("realized_gains"), read(service.exportRealizedGains()));
        }

        @Test
        void 交易紀錄() throws Exception {
            stubAll();
            assertSameWorkbook(golden("asset_transactions"), read(service.exportAssetTransactions()));
        }

        @Test
        void 油價金價() throws Exception {
            stubAll();
            assertSameWorkbook(golden("commodity"), read(service.exportCommodityPrices(D1, D2)));
        }

        @Test
        void 台幣兌美元匯率() throws Exception {
            stubAll();
            assertSameWorkbook(golden("exchange_rate"), read(service.exportExchangeRates("USD", D1, D2)));
        }

        @Test
        void 大盤指數日線() throws Exception {
            stubAll();
            assertSameWorkbook(golden("index_twse"), read(service.exportIndexDaily("TWSE", D1, D2)));
        }
    }

    @Nested
    @DisplayName("null 的兩種既有行為必須可分辨")
    class NullSemantics {

        @Test
        @DisplayName("已實現損益／交易紀錄的「交易日期」為 null 時是空字串格，不是 BLANK")
        void 交易日期為空字串格() throws Exception {
            stubAll();
            // 已實現損益：第 2 欄；第 2 列（index 2）是 tradeDate=null 的那筆
            Cell c1 = read(service.exportRealizedGains()).getSheetAt(0).getRow(2).getCell(2);
            assertThat(c1.getCellType()).isEqualTo(CellType.STRING);
            assertThat(c1.getStringCellValue()).isEmpty();
            // 交易紀錄：第 4 欄
            Cell c2 = read(service.exportAssetTransactions()).getSheetAt(0).getRow(2).getCell(4);
            assertThat(c2.getCellType()).isEqualTo(CellType.STRING);
            assertThat(c2.getStringCellValue()).isEmpty();
        }

        @Test
        @DisplayName("油價金價／匯率／指數的無值欄位是「完全不建格」，不是 BLANK 格")
        void 行情缺值時完全不建格() throws Exception {
            stubAll();
            // 油價金價：BRENT 全缺 → 第 1 列的第 2 欄不存在
            Row commodity = read(service.exportCommodityPrices(D1, D2)).getSheetAt(0).getRow(1);
            assertThat(commodity.getCell(2)).as("BRENT 缺值 → 該格不存在").isNull();
            // 匯率：D2 無牌告 → 第 2 列只剩日期一格
            Row rate = read(service.exportExchangeRates("USD", D1, D2)).getSheetAt(0).getRow(2);
            assertThat(rate.getCell(1)).isNull();
            assertThat(rate.getLastCellNum()).isEqualTo((short) 1);
            // 指數：D2 只有收盤 → open/high/low 三格不存在、收盤仍在
            Row idx = read(service.exportIndexDaily("TWSE", D1, D2)).getSheetAt(0).getRow(2);
            assertThat(idx.getCell(1)).isNull();
            assertThat(idx.getCell(3)).isNull();
            assertThat(idx.getCell(4).getNumericCellValue()).isEqualTo(23200.00);
        }
    }

    @Nested
    @DisplayName("JSON 型別與語意")
    class JsonSemantics {

        @Test
        @DisplayName("數值為 JSON number 且保留原精度，缺值為 null")
        void 數值與缺值() throws Exception {
            stubAll();
            JsonNode rows = json(service.exchangeRatesDoc("USD", D1, D2)).at("/sheets/0/tables/0/rows");
            assertThat(rows.get(0).get("即期買入").isNumber()).isTrue();
            assertThat(rows.get(0).get("即期買入").decimalValue()).isEqualByComparingTo("32.0000");
            assertThat(rows.get(1).get("即期買入").isNull()).as("缺值是 null，不是 0 或空字串").isTrue();

            JsonNode idx = json(service.indexDailyDoc("TWSE", D1, D2)).at("/sheets/0/tables/0/rows");
            assertThat(idx.get(1).get("開盤").isNull()).isTrue();
            assertThat(idx.get(1).get("收盤").decimalValue()).isEqualByComparingTo("23200.00");
        }

        @Test
        @DisplayName("Requirement 55 具名例外二：既有寫空字串的欄位，JSON 也是空字串")
        void 具名例外的空字串欄位() throws Exception {
            stubAll();
            JsonNode g = json(service.realizedGainsDoc()).at("/sheets/0/tables/0/rows/1/交易日期");
            assertThat(g.asText()).isEmpty();
            JsonNode t = json(service.assetTransactionsDoc()).at("/sheets/0/tables/0/rows/1/交易日期");
            assertThat(t.asText()).isEmpty();
        }

        @Test
        @DisplayName("rows 是物件陣列、key 順序等於表頭，且日期輸出 ISO")
        void 結構與日期() throws Exception {
            stubAll();
            JsonNode row0 = json(service.commodityPricesDoc(D1, D2)).at("/sheets/0/tables/0/rows/0");
            List<String> keys = new ArrayList<>();
            row0.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactly("日期", "WTI原油(USD/桶)", "布蘭特原油(USD/桶)", "黃金(USD/盎司)");
            assertThat(row0.get("日期").asText()).isEqualTo("2026-07-30");
        }
    }

    @Nested
    @DisplayName("手動下載與完整匯出零回歸")
    class ManualDownload {

        @Test
        @DisplayName("完整匯出仍含「已實現損益」分頁，且與單獨匯出的那一份逐格相同")
        void 完整匯出的已實現損益分頁未分裂() throws Exception {
            stubAll();
            when(snapshotRepo.findAllByOrderBySnapshotDateAsc()).thenReturn(List.of());

            Workbook full = read(service.exportFull());
            Sheet gainsInFull = full.getSheet("已實現損益");
            assertThat(gainsInFull).as("完整匯出必須仍有這張分頁").isNotNull();

            Workbook standalone = read(service.exportRealizedGains());
            Sheet gainsAlone = standalone.getSheetAt(0);
            assertThat(gainsInFull.getLastRowNum()).isEqualTo(gainsAlone.getLastRowNum());
            for (int ri = 0; ri <= gainsAlone.getLastRowNum(); ri++) {
                for (int ci = 0; ci < gainsAlone.getRow(ri).getLastCellNum(); ci++) {
                    Cell a = gainsInFull.getRow(ri).getCell(ci);
                    Cell b = gainsAlone.getRow(ri).getCell(ci);
                    assertThat(a == null).as("(%d,%d) 存在與否", ri, ci).isEqualTo(b == null);
                    if (b != null && b.getCellType() == CellType.STRING) {
                        assertThat(a.getStringCellValue()).isEqualTo(b.getStringCellValue());
                    }
                }
            }
        }
    }
}
