package com.steven.assets.service;

import com.steven.assets.model.AssetTransaction;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.CommodityPriceHistoryRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.RealizedGainRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.steven.assets.service.export.ExcelDocRenderer;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ExcelExportService 交易紀錄匯出測試（Requirement 49 / Task 237、268）。
 * 驗證 sheet 名為「交易紀錄」、17 欄表頭順序正確（Task 268 由 15 欄增為 17）、資料列數＝交易筆數，
 * 以及手續費／證交稅欄未填時為空白格（BLANK cell）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetTransactionExcelExportTest {

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

    /**
     * <b>刻意注入真的 {@link ExcelDocRenderer}（不是 mock）</b>：本測試是「既有 Excel 逐格零回歸」的守門，
     * 換成 mock 就什麼都驗不到了。Requirement 55 / Task 270 讓 ExcelExportService 多了這個依賴，
     * {@code @InjectMocks} 對未宣告成 {@code @Mock} 的欄位會塞 null，故用 {@code @Spy} 提供真實實例。
     * <b>下方所有斷言一字不得改</b>——它們變紅就是真回歸。
     */
    @Spy private ExcelDocRenderer excelDocRenderer = new ExcelDocRenderer();

    @InjectMocks private ExcelExportService service;

    private static final String[] EXPECTED_HEADERS = {
            "資產名稱","代號","交易類型","資產類型","交易日期","數量","單價","成交金額",
            "台幣成交金額","手續費","證交稅","市場","幣別","券商通路","匯率","年度","備註"};

    private static AssetTransaction tx(String type, String currency, LocalDate date,
                                       BigDecimal amount, BigDecimal rate) {
        return txWithCost(type, currency, date, amount, rate, null, null);
    }

    /** Task 268：帶手續費／證交稅的變體。 */
    private static AssetTransaction txWithCost(String type, String currency, LocalDate date,
                                               BigDecimal amount, BigDecimal rate,
                                               BigDecimal fee, BigDecimal transactionTax) {
        return AssetTransaction.builder()
                .transactionType(type).assetType("股票").assetName("台積電").assetCode("2330")
                .market("台股").currency(currency).channel("富邦").tradeDate(date)
                .shares(new BigDecimal("1000")).price(new BigDecimal("1000"))
                .amount(amount).fee(fee).transactionTax(transactionTax)
                .exchangeRate(rate).notes("備註").build();
    }

    @Test
    void 匯出交易紀錄_sheet名與17欄表頭與列數正確() throws Exception {
        when(assetTxRepo.findAllByOrderByTradeDateDesc()).thenReturn(List.of(
                tx("買", "TWD", LocalDate.of(2026, 7, 1), new BigDecimal("1000000"), null),
                tx("賣", "USD", LocalDate.of(2025, 3, 3), new BigDecimal("100"), new BigDecimal("32"))));

        byte[] data = service.exportAssetTransactions();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheet("交易紀錄");
            assertThat(sheet).isNotNull();

            Row header = sheet.getRow(0);
            for (int i = 0; i < EXPECTED_HEADERS.length; i++) {
                assertThat(header.getCell(i).getStringCellValue())
                        .as("表頭第 %d 欄", i).isEqualTo(EXPECTED_HEADERS[i]);
            }
            // 表頭 1 列 + 資料 2 列
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            // 台幣成交金額欄（第 9 欄 index 8）未因新增兩欄而位移：USD 列 = 100×32 = 3200
            assertThat(sheet.getRow(2).getCell(8).getNumericCellValue()).isEqualTo(3200.0);
        }
    }

    /** Task 268：手續費（index 9）／證交稅（index 10）有值時寫入正確、未填時為 BLANK cell。 */
    @Test
    void 手續費與證交稅欄_有值時寫入未填時為空白格() throws Exception {
        when(assetTxRepo.findAllByOrderByTradeDateDesc()).thenReturn(List.of(
                txWithCost("賣", "TWD", LocalDate.of(2026, 7, 1), new BigDecimal("27000"), null,
                        new BigDecimal("20"), new BigDecimal("81")),
                tx("買", "TWD", LocalDate.of(2025, 3, 3), new BigDecimal("1000"), null)));

        byte[] data = service.exportAssetTransactions();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheet("交易紀錄");

            Row withCost = sheet.getRow(1);
            assertThat(withCost.getCell(9).getNumericCellValue()).isEqualTo(20.0);
            assertThat(withCost.getCell(10).getNumericCellValue()).isEqualTo(81.0);
            // 台幣成交金額未被費用扣減（純記錄欄）
            assertThat(withCost.getCell(8).getNumericCellValue()).isEqualTo(27000.0);

            // 未填：cell() 先 createCell 才判 null，故格子存在但為 BLANK（非 null）
            Row withoutCost = sheet.getRow(2);
            assertThat(withoutCost.getCell(9).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(withoutCost.getCell(10).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(withoutCost.getCell(9, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).isNull();
        }
    }

    @Test
    void 零交易時仍產出含表頭的合法檔() throws Exception {
        when(assetTxRepo.findAllByOrderByTradeDateDesc()).thenReturn(List.of());

        byte[] data = service.exportAssetTransactions();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheet("交易紀錄");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getLastRowNum()).isEqualTo(0); // 只有表頭
            // 以 length-1 取最後一欄，日後再增欄不必回頭改這個硬編 index（Task 268 由 14 → 16）
            assertThat(sheet.getRow(0).getCell(EXPECTED_HEADERS.length - 1).getStringCellValue())
                    .isEqualTo("備註");
        }
    }
}
