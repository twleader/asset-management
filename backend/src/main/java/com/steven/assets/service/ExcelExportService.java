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
import java.util.List;

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
    private final StockRepository stockMasterRepo;

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

    /** 只匯出已實現損益 */
    @Transactional(readOnly = true)
    public byte[] exportRealizedGains() throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            writeRealizedGainsSheet(wb, st);
            wb.write(out);
            return out.toByteArray();
        }
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
            BigDecimal stockCostTwd = "USD".equals(sk.getCurrency()) && sk.getInvestmentCost() != null
                    ? sk.getInvestmentCost().multiply(
                            sk.getTransactionExchangeRate() != null ? sk.getTransactionExchangeRate()
                                    : (s.getUsdExchangeRate() != null ? s.getUsdExchangeRate() : BigDecimal.ONE))
                        .setScale(0, java.math.RoundingMode.HALF_UP)
                    : sk.getInvestmentCost();
            cell(row, 5, stockCostTwd, st.money);
            cell(row, 6, sk.getCurrentValue(), st.money);
            cell(row, 7, sk.getEstimatedDividend(), st.money);
            cell(row, 8, sk.getTransactionType(), null);
            cell(row, 9, sk.getTransactionDate() != null ? ISO.format(sk.getTransactionDate()) : "", null);
        }

        for (int i = 0; i < 10; i++) sheet.autoSizeColumn(i);
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
        }
    }
}
