package com.steven.assets.service;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.dto.RealizedGainDto;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.RealizedGainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final AssetService assetService;
    private final InstitutionService institutionService;
    private final RealizedGainRepository gainRepo;
    private final AssetSnapshotRepository snapshotRepo;

    private static final Pattern DATE_SHEET = Pattern.compile("^\\d{8}$");

    public record ImportResult(int snapshotsImported, int gainsImported, List<String> errors) {}

    public ImportResult importExcel(MultipartFile file) throws IOException {
        List<String> errors = new ArrayList<>();
        int snapshotsImported = 0;
        int gainsImported = 0;

        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            // Import date snapshots
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                String name = sheet.getSheetName();
                if (DATE_SHEET.matcher(name).matches()) {
                    try {
                        importSnapshot(sheet, name);
                        snapshotsImported++;
                    } catch (Exception e) {
                        log.warn("匯入快照 {} 失敗: {}", name, e.getMessage());
                        errors.add("快照 " + name + ": " + e.getMessage());
                    }
                }
            }

            // Import realized gains
            Sheet gainSheet = wb.getSheet("已實現損益");
            if (gainSheet != null) {
                try {
                    gainsImported = importRealizedGains(gainSheet);
                } catch (Exception e) {
                    log.warn("匯入已實現損益失敗: {}", e.getMessage());
                    errors.add("已實現損益: " + e.getMessage());
                }
            }
        }

        return new ImportResult(snapshotsImported, gainsImported, errors);
    }

    private void importSnapshot(Sheet sheet, String sheetName) {
        LocalDate date = parseSheetDate(sheetName);

        // Find date cell in row 0
        Row row0 = sheet.getRow(0);
        if (row0 != null) {
            Cell dateCell = row0.getCell(1);
            if (dateCell != null && dateCell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(dateCell)) {
                date = dateCell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
        }

        List<AssetSnapshotDto.DepositRequest> deposits = new ArrayList<>();
        List<AssetSnapshotDto.FundRequest> funds = new ArrayList<>();
        List<AssetSnapshotDto.StockRequest> stocks = new ArrayList<>();
        BigDecimal usdRate = null;

        String currentBank = null;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            String col0 = getString(row, 0);
            String col1 = getString(row, 1);
            String col2 = getString(row, 2);

            // 美元匯率
            if ("美元匯率".equals(col0) || "美元匯率".equals(col1)) {
                Cell rateCell = row.getCell("美元匯率".equals(col0) ? 1 : 2);
                if (rateCell != null) usdRate = getBigDecimal(row, "美元匯率".equals(col0) ? 1 : 2);
            }

            // 資產小計/總計 - skip
            if ("資產小計".equals(col0) || "資產總計".equals(col0)) continue;

            // Track current bank
            if (col0 != null && !col0.isBlank() && !"銀行別".equals(col0)) {
                currentBank = col0.replace("\n", "");
            }

            // 存款 (不再依賴 getString 判斷 col2，直接用 getBigDecimal，以支援 Formula 儲存格)
            if (col1 != null && !col1.isBlank() && isDepositType(col1)) {
                BigDecimal twdAmount = getBigDecimal(row, 2);
                if (twdAmount != null && twdAmount.compareTo(BigDecimal.ZERO) != 0) {
                    Long bankId = institutionService.matchBankByKeyword(currentBank)
                            .map(b -> b.getId()).orElse(null);
                    String depositType = resolveDepositType(col1);
                    if (depositType != null) {
                        String currency = "美元活存".equals(depositType) || "美元定存".equals(depositType)
                                || col1.contains("美元") ? "USD" : "TWD";
                        // Excel 欄位存的是台幣換算值；USD 存款需反推原幣金額
                        BigDecimal originalAmount = null;
                        if ("USD".equals(currency) && usdRate != null && usdRate.compareTo(BigDecimal.ZERO) > 0) {
                            originalAmount = twdAmount.divide(usdRate, 2, RoundingMode.HALF_UP);
                        }
                        deposits.add(new AssetSnapshotDto.DepositRequest(
                            bankId, depositType, twdAmount, originalAmount, currency, null, null
                        ));
                    }
                }
            }

            // 信託基金 (col3=fund name, col4=invest, col5=value)
            String fundName = getString(row, 3);
            if (fundName != null && !fundName.isBlank()) {
                BigDecimal invest = getBigDecimal(row, 4);
                BigDecimal value = getBigDecimal(row, 5);
                if (invest != null && invest.compareTo(BigDecimal.ZERO) > 0 && value != null) {
                    Long bankId = institutionService.matchBankByKeyword(currentBank)
                            .map(b -> b.getId()).orElse(null);
                    funds.add(new AssetSnapshotDto.FundRequest(fundName, null, bankId, invest, value, null, null));
                }
            }

            // 股票欄位：偵測新舊格式
            // 新格式 (2024+): col6=代號, col7=名稱(字串), col8=股數, col9=成本, col10=現值, col11=配息
            // 舊格式 (2023-): col6=代號(含名稱), col7=股數(數字), col8=成本, col9=現值
            String stockCode = getString(row, 6);
            if (stockCode != null && !stockCode.isBlank()
                    && !"台股".equals(stockCode) && !"美股".equals(stockCode)
                    && !"股票代號".equals(stockCode) && !stockCode.contains("NaN")) {

                // 判斷是新格式(col7是字串名稱)還是舊格式(col7是數字股數)
                BigDecimal col7AsNum = getBigDecimal(row, 7);
                boolean isNewFormat = (col7AsNum == null); // col7 是字串 → 新格式

                String stockName;
                BigDecimal shares, cost, value, div;

                if (isNewFormat) {
                    // 新格式
                    stockName = getString(row, 7);
                    shares = getBigDecimal(row, 8);
                    cost   = getBigDecimal(row, 9);
                    value  = getBigDecimal(row, 10);
                    div    = getBigDecimal(row, 11);
                } else {
                    // 舊格式：代號可能含名稱 (e.g. "00642 元大石油")
                    String[] parts = stockCode.trim().split("\\s+", 2);
                    stockCode = parts[0];
                    stockName = parts.length > 1 ? parts[1] : parts[0];
                    shares = col7AsNum;
                    cost   = getBigDecimal(row, 8);
                    value  = getBigDecimal(row, 9);
                    div    = null;
                }

                if (shares != null && cost != null && value != null
                        && cost.compareTo(BigDecimal.ZERO) > 0) {
                    String market = isUsStock(stockCode) ? "美股" : "台股";
                    Long brokerId = institutionService.matchBrokerByKeyword(currentBank)
                            .map(b -> b.getId()).orElse(null);
                    stocks.add(new AssetSnapshotDto.StockRequest(
                        stockCode, stockName != null ? stockName : stockCode,
                        market, brokerId,
                        shares, cost, value, div, null, "TWD", null,
                        null, null, null
                    ));
                }
            }
        }

        AssetSnapshotDto.CreateSnapshotRequest req = new AssetSnapshotDto.CreateSnapshotRequest(
            date, usdRate, null, deposits, funds, stocks
        );

        // 若已存在同日期快照，先刪除再重建（覆蓋匯入）
        final LocalDate finalDate = date;
        snapshotRepo.findBySnapshotDate(finalDate).ifPresent(existing -> {
            log.info("快照 {} 已存在，刪除後重新匯入", finalDate);
            snapshotRepo.deleteById(existing.getId());
            snapshotRepo.flush();
        });
        assetService.createSnapshot(req);
    }

    /**
     * 從獨立 Excel 檔案匯入已實現損益。
     * 支援含「已實現損益」工作表的檔案，或直接以第一個工作表作為資料來源。
     */
    public ImportResult importRealizedGainsFromFile(MultipartFile file) throws IOException {
        List<String> errors = new ArrayList<>();
        int gainsImported = 0;
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet gainSheet = wb.getSheet("已實現損益");
            if (gainSheet == null && wb.getNumberOfSheets() > 0) {
                gainSheet = wb.getSheetAt(0);
            }
            if (gainSheet != null) {
                try {
                    gainsImported = importRealizedGains(gainSheet);
                } catch (Exception e) {
                    log.warn("匯入已實現損益失敗: {}", e.getMessage());
                    errors.add(e.getMessage());
                }
            } else {
                errors.add("找不到「已實現損益」工作表");
            }
        }
        return new ImportResult(0, gainsImported, errors);
    }

    private int importRealizedGains(Sheet sheet) {
        // 匯入前先清空所有已實現損益，避免重複匯入產生雙倍資料
        gainRepo.deleteAll();
        gainRepo.flush();

        int count = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            String name = getString(row, 0);
            if (name == null || name.isBlank()) continue;
            if (name.contains("年度") || "股票名稱".equals(name)) continue;

            String code = getString(row, 1);
            Cell dateCell = row.getCell(2);
            if (dateCell == null) continue;

            LocalDate tradeDate = null;
            if (dateCell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(dateCell)) {
                tradeDate = dateCell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            if (tradeDate == null) continue;

            BigDecimal proceeds = getBigDecimal(row, 5);
            BigDecimal cost = getBigDecimal(row, 6);

            if (proceeds == null || cost == null) continue;

            String market = code != null && isUsStock(code) ? "美股" : "台股";

            try {
                // Excel 損益欄位均為台幣（已實現損益工作表統一用台幣記錄），
                // 明確傳入 "TWD" 避免系統誤判美股代號而套用匯率換算
                assetService.createRealizedGain(new RealizedGainDto.CreateRealizedGainRequest(
                    name, code, market, "TWD", null, tradeDate,
                    getBigDecimal(row, 3), getBigDecimal(row, 4),
                    proceeds, cost
                ));
                count++;
            } catch (Exception e) {
                log.warn("跳過損益記錄 row={} name={}: {}", r, name, e.getMessage());
            }
        }
        return count;
    }

    // ===== Helpers =====

    private LocalDate parseSheetDate(String name) {
        int year = Integer.parseInt(name.substring(0, 4));
        int month = Integer.parseInt(name.substring(4, 6));
        int day = Integer.parseInt(name.substring(6, 8));
        return LocalDate.of(year, month, day);
    }

    private String getString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> null;
        };
    }

    private BigDecimal getBigDecimal(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        try {
            // 先判斷實際型別（FORMULA 要用 getCachedFormulaResultType）
            CellType effectiveType = cell.getCellType();
            if (effectiveType == CellType.FORMULA) {
                effectiveType = cell.getCachedFormulaResultType();
            }
            return switch (effectiveType) {
                case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).setScale(4, RoundingMode.HALF_UP);
                case STRING -> {
                    try {
                        yield new BigDecimal(cell.getStringCellValue().trim());
                    } catch (Exception e) {
                        yield null;
                    }
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private String getStringFull(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) yield null;
                yield String.valueOf((long) cell.getNumericCellValue());
            }
            case FORMULA -> {
                try {
                    yield String.valueOf((long) cell.getNumericCellValue());
                } catch (Exception e) {
                    try { yield cell.getStringCellValue().trim(); }
                    catch (Exception e2) { yield null; }
                }
            }
            default -> null;
        };
    }

    private boolean isDepositType(String s) {
        return s.contains("活存") || s.contains("定存") || s.contains("美元") ||
               s.contains("綜存") || s.contains("儲蓄") || s.contains("證券戶") ||
               s.contains("行存") || s.contains("員離") || s.contains("子帳戶") ||
               s.contains("財金") || s.contains("主帳戶") || s.contains("口袋") ||
               s.contains("信用卡");
    }

    private boolean isUsStock(String code) {
        return code != null && code.matches("[A-Z]{1,5}.*") && !code.matches("\\d.*");
    }

    private String resolveDepositType(String raw) {
        if (raw == null) return null;
        if (raw.contains("美元") && raw.contains("定存")) return "美元定存";
        if (raw.contains("美元")) return "美元活存";
        if (raw.contains("定存")) return "定存";
        if (raw.contains("信用卡")) return "信用卡待付款";
        if (raw.contains("證券")) return "證券戶";
        return "活存";
    }
}
