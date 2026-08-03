package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.CommodityPriceHistory;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.RealizedGain;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
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

    // ===== Task 285：大盤指數日線加第六欄「週線MA5」 =====

    @Nested
    @DisplayName("Task 285／286：大盤指數日線的四條均線欄")
    class WeeklyMa5 {

        /** 匯出區間；本組 fixture 的日期一律 >= START（285.15），回看測試另備 fixture（285.16）。 */
        private static final LocalDate START = LocalDate.of(2026, 7, 1);
        private static final LocalDate END = LocalDate.of(2026, 7, 31);

        /** 長天期均線 fixture 的起始日（286.12）；與 START/END 無關，日期一律 >= 本值，不觸發回看過濾。 */
        private static final LocalDate LONG_START = LocalDate.of(2025, 1, 1);

        @Test
        @DisplayName("插欄前後既有五欄逐格未變（對 index_twse_pre_t285 做 identity 映射比對）")
        void 插欄前後既有五欄未變() throws Exception {
            stubAll();
            Workbook actual = read(service.exportIndexDaily("TWSE", D1, D2));
            Workbook pre = golden("index_twse_pre_t285");

            // 附加在最末 → 欄索引不位移，映射即 identity（t281 插在中間時才需要位移函式）
            assertSameMapped(pre, actual, c -> c);

            // 新欄只出現在表頭列：fixture 僅 2 列、湊不滿任何視窗，故資料列該格根本不建（omitNullCells）
            Sheet s = actual.getSheetAt(0);
            assertThat(s.getRow(0).getCell(5).getStringCellValue()).isEqualTo("週線MA5");
            // Task 289 插欄後表頭共 11 格（不是 t285 當下的 6 格）——本測試只驗「t285 插的那一欄未變」，
            // 表格總欄數已因 t286／t289 變動，這裡必須跟著改，否則會誤判後面的任務打壞了東西。
            assertThat(s.getRow(0).getLastCellNum()).isEqualTo((short) 11);
            assertThat(s.getRow(1).getCell(5)).as("視窗未滿 → 該格不建，不是 BLANK").isNull();
            assertThat(s.getRow(2).getCell(5)).isNull();
        }

        @Test
        @DisplayName("插欄前後既有六欄逐格未變（對 index_twse_pre_t286 做 identity 映射比對）")
        void 插欄前後既有六欄未變() throws Exception {
            stubAll();
            Workbook actual = read(service.exportIndexDaily("TWSE", D1, D2));
            Workbook pre = golden("index_twse_pre_t286");

            // 附加在最末 → 欄索引不位移，映射即 identity
            assertSameMapped(pre, actual, c -> c);

            Sheet s = actual.getSheetAt(0);
            assertThat(s.getRow(0).getCell(6).getStringCellValue()).isEqualTo("月線MA20");
            assertThat(s.getRow(0).getCell(7).getStringCellValue()).isEqualTo("季線MA60");
            assertThat(s.getRow(0).getCell(8).getStringCellValue()).isEqualTo("年線MA240");
            assertThat(s.getRow(0).getLastCellNum()).isEqualTo((short) 11);
            // fixture 僅 2 列，三個新視窗全部湊不滿，資料列該格根本不建
            assertThat(s.getRow(1).getCell(6)).isNull();
            assertThat(s.getRow(1).getCell(7)).isNull();
            assertThat(s.getRow(1).getCell(8)).isNull();
        }

        @Test
        @DisplayName("MA5＝含當日往前 5 個交易日收盤的精確平均（2 位 HALF_UP），前 4 列無值；JSON 同值")
        void MA5值與JSON() throws Exception {
            when(twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc(any(), any()))
                    .thenReturn(sevenDays());

            Sheet s = read(service.exportIndexDaily("TWSE", START, END)).getSheetAt(0);
            for (int i = 1; i <= 4; i++) {
                assertThat(s.getRow(i).getCell(5)).as("第 %d 列視窗未滿 → 該格不建", i).isNull();
            }
            // 期望值在測試內手算，不呼叫被測程式自己算：
            //   前 5 筆 100.00+100.01+100.02+100.03+100.05 = 500.11 → /5 = 100.022 → HALF_UP(2) = 100.02
            assertThat(s.getRow(5).getCell(5).getNumericCellValue()).isEqualTo(100.02);
            //   第 2~6 筆 100.01+100.02+100.03+100.05+100.03 = 500.14 → /5 = 100.028 → HALF_UP(2) = 100.03（進位）
            assertThat(s.getRow(6).getCell(5).getNumericCellValue()).isEqualTo(100.03);
            //   第 3~7 筆 100.02+100.03+100.05+100.03+100.07 = 500.20 → /5 = 100.04（整除）
            assertThat(s.getRow(7).getCell(5).getNumericCellValue()).isEqualTo(100.04);
            // 2 位小數的收盤價除以 5 恆為 3 位小數且末位為偶數（k/500 = 2k/1000），
            // 故 x.xxx5 的 HALF_UP 平手在 TWSE 精度下數學上不可能出現——上面第二筆的「進位」即為最強案例。
            assertThat(s.getRow(5).getCell(5).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");

            JsonNode rows = json(service.indexDailyDoc("TWSE", START, END)).at("/sheets/0/tables/0/rows");
            assertThat(rows.get(0).get("週線MA5").isNull()).as("JSON 前 4 列為 null，不是 0").isTrue();
            assertThat(rows.get(3).get("週線MA5").isNull()).isTrue();
            assertThat(rows.get(4).get("週線MA5").decimalValue()).isEqualByComparingTo("100.02");
            assertThat(rows.get(5).get("週線MA5").decimalValue()).isEqualByComparingTo("100.03");
        }

        @Test
        @DisplayName("查詢向前回看 400 天，但回看列不得出現在檔案中（第一列即 start 且已有 MA5）")
        void 回看列不輸出() throws Exception {
            when(twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc(any(), any()))
                    .thenReturn(withLookback());

            Sheet s = read(service.exportIndexDaily("TWSE", START, END)).getSheetAt(0);

            ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
            verify(twseIndexHistRepo)
                    .findByTradingDateBetweenOrderByTradingDateAsc(from.capture(), any());
            assertThat(from.getValue()).as("查詢起點須回看 400 個日曆天（Task 286 由 30 天放大）")
                    .isEqualTo(START.minusDays(400));

            assertThat(s.getLastRowNum()).as("4 筆回看列不得輸出，只剩表頭 ＋ 3 列").isEqualTo(3);
            assertThat(s.getRow(1).getCell(0).getStringCellValue())
                    .as("第一列即 start，不是回看起點").isEqualTo(START.toString());
            //   回看的 4 筆 ＋ start 當天 = 100.00+100.01+100.02+100.03+100.05 = 500.11 → 100.02
            assertThat(s.getRow(1).getCell(5).getNumericCellValue())
                    .as("第一列就要有值——這正是回看存在的理由").isEqualTo(100.02);
        }

        @Test
        @DisplayName("月線／季線／年線＝含當日往前對應交易日數收盤的精確平均，各自暖機列準確；JSON 同值")
        void 長天期均線值與JSON() throws Exception {
            when(twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc(any(), any()))
                    .thenReturn(longSeries());
            LocalDate end = LONG_START.plusDays(250);

            Sheet s = read(service.exportIndexDaily("TWSE", LONG_START, end)).getSheetAt(0);

            // MA20（欄索引 6）：暖機 19 列（data index 0..18），index 19 起有值
            assertThat(s.getRow(19).getCell(6)).as("MA20 暖機未滿（data index 18）→ 該格不建").isNull();
            // 期望值在測試內手算：close[i] = 100.00 + i*0.01，MA20 於 data index 19
            //   = 前 20 筆(index 0..19)之和 2001.90 / 20 = 100.095 → HALF_UP(2) = 100.10
            assertThat(s.getRow(20).getCell(6).getNumericCellValue()).isEqualTo(100.10);

            // MA60（欄索引 7）：暖機 59 列，index 59 起有值
            assertThat(s.getRow(59).getCell(7)).as("MA60 暖機未滿（data index 58）→ 該格不建").isNull();
            //   前 60 筆(index 0..59)之和 6017.70 / 60 = 100.295 → HALF_UP(2) = 100.30
            assertThat(s.getRow(60).getCell(7).getNumericCellValue()).isEqualTo(100.30);

            // MA240（欄索引 8）：暖機 239 列，index 239 起有值
            assertThat(s.getRow(239).getCell(8)).as("MA240 暖機未滿（data index 238）→ 該格不建").isNull();
            //   前 240 筆(index 0..239)之和 24286.80 / 240 = 101.195 → HALF_UP(2) = 101.20
            assertThat(s.getRow(240).getCell(8).getNumericCellValue()).isEqualTo(101.20);

            JsonNode rows = json(service.indexDailyDoc("TWSE", LONG_START, end)).at("/sheets/0/tables/0/rows");
            assertThat(rows.get(18).get("月線MA20").isNull()).as("JSON 暖機列為 null，不是 0").isTrue();
            assertThat(rows.get(19).get("月線MA20").decimalValue()).isEqualByComparingTo("100.10");
            assertThat(rows.get(58).get("季線MA60").isNull()).isTrue();
            assertThat(rows.get(59).get("季線MA60").decimalValue()).isEqualByComparingTo("100.30");
            assertThat(rows.get(238).get("年線MA240").isNull()).isTrue();
            assertThat(rows.get(239).get("年線MA240").decimalValue()).isEqualByComparingTo("101.20");
        }

        @Test
        @DisplayName("回看視窗需涵蓋 239 個交易日以支撐 MA240（回看列不得輸出，第一列即有 MA240）")
        void 回看支撐MA240() throws Exception {
            LocalDate rangeStart = LocalDate.of(2026, 1, 1);
            when(twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc(any(), any()))
                    .thenReturn(lookbackFor240(rangeStart));

            Sheet s = read(service.exportIndexDaily("TWSE", rangeStart, rangeStart)).getSheetAt(0);

            ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
            verify(twseIndexHistRepo)
                    .findByTradingDateBetweenOrderByTradingDateAsc(from.capture(), any());
            assertThat(from.getValue()).isEqualTo(rangeStart.minusDays(400));

            assertThat(s.getLastRowNum()).as("239 筆回看列不得輸出，只剩表頭 ＋ 1 列").isEqualTo(1);
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo(rangeStart.toString());
            assertThat(s.getRow(1).getCell(8))
                    .as("回看足夠（239 筆歷史 ＋ 當日）→ 第一列 MA240 就有值").isNotNull();
        }

        // ===== Task 289：加「成交股數」「成交金額」兩欄 =====

        @Test
        @DisplayName("插欄後既有九欄逐格未變（對 index_twse_pre_t289 做 identity 映射比對）")
        void 插欄後既有九欄未變() throws Exception {
            stubAll();
            Workbook actual = read(service.exportIndexDaily("TWSE", D1, D2));
            Workbook pre = golden("index_twse_pre_t289");

            // 附加在最末 → 欄索引不位移，映射即 identity；驗到欄索引 0–8（日期～年線MA240）逐格未變
            assertSameMapped(pre, actual, c -> c);

            Sheet s = actual.getSheetAt(0);
            assertThat(s.getRow(0).getCell(9).getStringCellValue()).isEqualTo("成交股數");
            assertThat(s.getRow(0).getCell(10).getStringCellValue()).isEqualTo("成交金額");
            assertThat(s.getRow(0).getLastCellNum()).isEqualTo((short) 11);
        }

        @Test
        @DisplayName("成交股數／成交金額直接取 entity 欄位值，NUM0 格式無小數位；既有欄索引不受影響；JSON 同值")
        void 成交股數成交金額值與JSON() throws Exception {
            when(twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc(any(), any()))
                    .thenReturn(twseIndexWithVolume());

            Sheet s = read(service.exportIndexDaily("TWSE", D1, D1)).getSheetAt(0);
            assertThat(s.getRow(0).getLastCellNum()).isEqualTo((short) 11);
            assertThat(s.getRow(1).getCell(9).getNumericCellValue()).isEqualTo(14683404939d);
            assertThat(s.getRow(1).getCell(10).getNumericCellValue()).isEqualTo(1367817795171d);
            assertThat(s.getRow(1).getCell(9).getCellStyle().getDataFormatString())
                    .as("整數欄不得謊稱小數精度").isEqualTo("#,##0");
            // 既有欄索引 0/4（日期／收盤）未受新增兩欄影響
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo(D1.toString());
            assertThat(s.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(23050.00);

            JsonNode rows = json(service.indexDailyDoc("TWSE", D1, D1)).at("/sheets/0/tables/0/rows");
            assertThat(rows.get(0).size()).as("十一欄").isEqualTo(11);
            assertThat(rows.get(0).get("成交股數").asLong()).isEqualTo(14683404939L);
            assertThat(rows.get(0).get("成交金額").decimalValue()).isEqualByComparingTo("1367817795171");
        }

        @Test
        @DisplayName("海外指數：成交股數取 volume，成交金額固定為 null（無此資料，非計算值）")
        void 海外指數成交金額固定為null() throws Exception {
            when(usIndexHistRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(anyString(), any(), any()))
                    .thenReturn(djiWithVolume());

            Sheet s = read(service.exportIndexDaily("DJI", D1, D1)).getSheetAt(0);
            assertThat(s.getRow(1).getCell(9).getNumericCellValue()).isEqualTo(300000000d);
            assertThat(s.getRow(1).getCell(10)).as("海外指數無成交金額資料，該格根本不建，不是 BLANK").isNull();

            JsonNode rows = json(service.indexDailyDoc("DJI", D1, D1)).at("/sheets/0/tables/0/rows");
            assertThat(rows.get(0).get("成交股數").asLong()).isEqualTo(300000000L);
            assertThat(rows.get(0).get("成交金額").isNull()).as("JSON 亦為 null，不是 0").isTrue();
        }

        /** 單日、含成交股數／成交金額（供 289.7(b) 值比對，不動 {@code twseIndex()} fixture）。 */
        private static List<TwseIndexDailyHistory> twseIndexWithVolume() {
            TwseIndexDailyHistory a = new TwseIndexDailyHistory();
            a.setTradingDate(D1); a.setOpenPoint(new BigDecimal("23000.00"));
            a.setHighPoint(new BigDecimal("23100.00")); a.setLowPoint(new BigDecimal("22900.00"));
            a.setClosePoint(new BigDecimal("23050.00"));
            a.setTradeVolume(14683404939L);
            a.setTradeValue(new BigDecimal("1367817795171"));
            return List.of(a);
        }

        /** 單日海外指數（DJI），含 volume（供 289.7(c) 值比對）。 */
        private static List<UsIndexDailyHistory> djiWithVolume() {
            UsIndexDailyHistory a = new UsIndexDailyHistory();
            a.setIndexCode("DJI"); a.setTradingDate(D1);
            a.setOpenPoint(new BigDecimal("40000.0000")); a.setHighPoint(new BigDecimal("40100.0000"));
            a.setLowPoint(new BigDecimal("39900.0000")); a.setClosePoint(new BigDecimal("40050.0000"));
            a.setVolume(300000000L);
            return List.of(a);
        }

        /** 7 個交易日、全部 >= START（模擬 DB 歷史最前端湊不滿 5 筆）。 */
        private static List<TwseIndexDailyHistory> sevenDays() {
            String[] closes = {"100.00", "100.01", "100.02", "100.03", "100.05", "100.03", "100.07"};
            int[] days = {1, 2, 3, 6, 7, 8, 9};   // 2026-07 的平日
            List<TwseIndexDailyHistory> out = new ArrayList<>();
            for (int i = 0; i < closes.length; i++) {
                out.add(day(LocalDate.of(2026, 7, days[i]), closes[i]));
            }
            return out;
        }

        /** 前 4 筆落在 START 之前（回看列）、後 3 筆自 START 起。 */
        private static List<TwseIndexDailyHistory> withLookback() {
            String[] closes = {"100.00", "100.01", "100.02", "100.03", "100.05", "100.03", "100.07"};
            int[] june = {24, 25, 26, 29};                 // 回看列
            List<TwseIndexDailyHistory> out = new ArrayList<>();
            for (int i = 0; i < 4; i++) out.add(day(LocalDate.of(2026, 6, june[i]), closes[i]));
            for (int i = 4; i < 7; i++) out.add(day(LocalDate.of(2026, 7, i - 3), closes[i]));
            return out;
        }

        private static TwseIndexDailyHistory day(LocalDate d, String close) {
            TwseIndexDailyHistory h = new TwseIndexDailyHistory();
            h.setTradingDate(d);
            h.setClosePoint(new BigDecimal(close));
            return h;
        }

        /**
         * 245 個交易日、全部 >= LONG_START（不觸發回看過濾），收盤 close[i] = 100.00 + i×0.01（i 為
         * 0-based data index），刻意取等差數列——MA20/60/240 在各自暖機邊界的和恰為 x.x95，
         * HALF_UP 捨入必進位，是可在測試內手算、且不依賴被測程式自身的期望值。
         */
        private static List<TwseIndexDailyHistory> longSeries() {
            List<TwseIndexDailyHistory> out = new ArrayList<>();
            for (int i = 0; i < 245; i++) {
                out.add(day(LONG_START.plusDays(i), new BigDecimal("100.00").add(BigDecimal.valueOf(i, 2)).toPlainString()));
            }
            return out;
        }

        /** 239 個交易日的回看列（皆早於 rangeStart）＋ 區間內剛好 1 筆，收盤全相同以讓 MA240 易驗證非 null。 */
        private static List<TwseIndexDailyHistory> lookbackFor240(LocalDate rangeStart) {
            List<TwseIndexDailyHistory> out = new ArrayList<>();
            for (int i = 239; i >= 1; i--) {
                out.add(day(rangeStart.minusDays(i), "100.00"));
            }
            out.add(day(rangeStart, "100.00"));
            return out;
        }
    }

    /**
     * 以欄索引映射逐格比對值、型別、dataFormat、粗體、字級（Task 285，比照
     * {@code TradingRadarDualFormatTest.assertSameMapped}——那支是別的測試類別的 private static，跨類別用不到）。
     *
     * <p><b>不可改用同檔的 {@link #assertSameWorkbook}</b>：它會斷言 {@code getLastCellNum()} 相等，
     * 而表頭列插欄前後是 5 vs 6，必紅。<b>只重產 golden 不做這條比對也是不夠的</b>——
     * 那樣「新增了欄」與「順手把既有欄改壞」無法分辨。
     */
    private static void assertSameMapped(Workbook expected, Workbook actual,
                                         java.util.function.IntUnaryOperator map) {
        Sheet e = expected.getSheetAt(0);
        Sheet a = actual.getSheetAt(0);
        assertThat(a.getSheetName()).as("分頁名").isEqualTo(e.getSheetName());
        assertThat(a.getLastRowNum()).as("列數").isEqualTo(e.getLastRowNum());
        for (int ri = 0; ri <= e.getLastRowNum(); ri++) {
            Row er = e.getRow(ri);
            if (er == null) continue;
            Row ar = a.getRow(ri);
            assertThat(ar).as("第 %d 列", ri).isNotNull();
            for (int ci = 0; ci < er.getLastCellNum(); ci++) {
                Cell ec = er.getCell(ci);
                int target = map.applyAsInt(ci);
                Cell ac = ar.getCell(target);
                String where = String.format("舊(%d,%d) → 新(%d,%d)", ri, ci, ri, target);
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
