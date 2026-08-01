package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingCalendarExportDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 交易日曆匯出到指定路徑（Requirement 37 / Task 184）。
 *
 * <p>把某一年度整年交易日曆（台／美／英三市每日交易日旗標 ＋ 各市場國定假日）以 JSON 或 Excel 寫檔到
 * 使用者指定目錄。資料判斷全走 {@link MarketDataService}（與頁面日曆格、市場狀態同一權威來源），前端只 render。
 *
 * <p>輸出路徑沿用 Requirement 34 的家目錄為根 ＋ 相對子路徑安全模型：實際寫入 = 容器基底 {@code EXPORT_OUTPUT_DIR}
 * resolve 使用者子路徑，並驗證 normalize 後仍在基底內（拒 {@code ..}／絕對路徑跳脫）。刻意不改動
 * {@link ExportScheduleService}（已上線含租戶隔離的排程功能，零回歸優先）；browse 為無狀態列目錄、非財務
 * 「同義欄位」，故重用其安全模型而各自實作。
 *
 * <p>非 owner-scoped：交易日曆為市場公開資料、輸出為檔案系統操作，本服務不注入 {@code CurrentUserContext}、
 * 不做 owner 過濾（與 {@link com.steven.assets.controller.MarketDataController} 假日端點一致）；存取控制靠 BFF 登入驗證。
 */
@Service
@Slf4j
public class TradingCalendarExportService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** DayOfWeek MONDAY(1)..SUNDAY(7) → 中文星期。 */
    private static final String[] WEEKDAY_ZH = {"一", "二", "三", "四", "五", "六", "日"};

    private final MarketDataService marketDataService;
    private final ObjectMapper objectMapper;
    // 雙格式匯出（Requirement 55 / Task 271）：兩份檔的落地、tmp＋atomic move 與狀態字串一律走共用元件
    private final com.steven.assets.service.export.DualFormatExportWriter dualWriter;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host（見 docker-compose.yml），與 Requirement 34 共用同一 volume。 */
    private final String baseDir;

    public TradingCalendarExportService(MarketDataService marketDataService,
                                        ObjectMapper objectMapper,
                                        com.steven.assets.service.export.DualFormatExportWriter dualWriter,
                                        @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.marketDataService = marketDataService;
        this.objectMapper = objectMapper;
        this.dualWriter = dualWriter;
        this.baseDir = baseDir;
    }

    // ===== 匯出 =====

    /**
     * 產出指定年度整年交易日曆並以指定格式寫檔到 {@code subpath} 目錄。
     *
     * <p><b>一律同時產出 JSON 與 Excel 兩份，主檔名相同</b>（Requirement 55 / Task 271）——
     * 使用者不再需要二選一。
     *
     * <p><b>本匯出點刻意不接 {@code ExportDoc}</b>：它本來就有兩個 builder、且共吃同一份
     * {@link #buildDays}，而 {@link #buildJson} 的輸出是對外契約、形狀不得改變。改接中介模型
     * 只會改變既有輸出、沒有任何好處。只把落檔換成共用元件（取得 tmp＋atomic move 與統一狀態字串）。
     *
     * <p>Drive 上傳<b>不在此處</b>：本方法沒有 owner 與 Drive 設定，由排程服務負責上傳兩份。
     *
     * @param year   西元年（1970..2100）
     * @param subpath 相對家目錄根的子路徑（空＝家目錄根）
     */
    public TradingCalendarExportDto.RunResponse exportToDir(int year, String subpath) {
        if (year < 1970 || year > 2100) {
            throw new IllegalArgumentException("年度必須介於 1970～2100：" + year);
        }
        String sub = normalizeSubpath(subpath);

        List<DayRow> days = buildDays(year);
        try {
            byte[] json = buildJson(year, days);
            byte[] xlsx = buildExcel(year, days);
            // gdriveEnabled=false：本方法只寫本機兩份，Drive 由排程服務處理（見 271.3.2.1）
            var r = dualWriter.write(null, resolveDir(sub), "交易日曆_" + year, json, xlsx, false, null);
            log.info("交易日曆匯出 year={} → {}", year, r.localStatus());
            return TradingCalendarExportDto.RunResponse.builder()
                    // 既有欄位語意不變：指 xlsx 那一份
                    .path(r.xlsxFile() == null ? null : r.xlsxFile().toString())
                    .sizeBytes(r.xlsxFile() == null ? 0 : (int) Files.size(r.xlsxFile()))
                    .jsonPath(r.jsonFile() == null ? null : r.jsonFile().toString())
                    .jsonSizeBytes(r.jsonFile() == null ? 0 : (int) Files.size(r.jsonFile()))
                    .localStatus(r.localStatus())
                    .year(year)
                    .totalDays(days.size())
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("交易日曆匯出失敗：" + e.getMessage(), e);
        }
    }

    /** 逐日建整年交易日曆（資料單一來源＝MarketDataService）。 */
    private List<DayRow> buildDays(int year) {
        Map<String, String> tw = marketDataService.getTwHolidays(year);
        Map<String, String> us = marketDataService.getUsHolidays(year);
        Map<String, String> uk = marketDataService.getUkHolidays(year);

        List<DayRow> rows = new ArrayList<>(366);
        LocalDate d = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        while (!d.isAfter(end)) {
            String date = d.toString();
            rows.add(new DayRow(
                    date,
                    WEEKDAY_ZH[d.getDayOfWeek().getValue() - 1],
                    marketDataService.isTwTradingDay(d),
                    marketDataService.isUsTradingDay(d),
                    marketDataService.isUkTradingDay(d),
                    tw.get(date),
                    us.get(date),
                    uk.get(date)));
            d = d.plusDays(1);
        }
        return rows;
    }

    /** 組 JSON 結構（UTF-8 pretty-print）；holidays 以 TreeMap 依日期排序、確保輸出穩定。 */
    private byte[] buildJson(int year, List<DayRow> days) throws IOException {
        long twCount = days.stream().filter(DayRow::tw).count();
        long usCount = days.stream().filter(DayRow::us).count();
        long ukCount = days.stream().filter(DayRow::uk).count();

        Map<String, Object> count = new LinkedHashMap<>();
        count.put("tw", twCount);
        count.put("us", usCount);
        count.put("uk", ukCount);

        Map<String, Object> holidays = new LinkedHashMap<>();
        holidays.put("tw", new TreeMap<>(marketDataService.getTwHolidays(year)));
        holidays.put("us", new TreeMap<>(marketDataService.getUsHolidays(year)));
        holidays.put("uk", new TreeMap<>(marketDataService.getUkHolidays(year)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("year", year);
        payload.put("generatedAt", LocalDateTime.now(TW_ZONE).format(TS_FMT));
        payload.put("timezone", TW_ZONE.getId());
        payload.put("tradingDayCount", count);
        payload.put("holidays", holidays);
        payload.put("days", days);

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
    }

    /** 組 Excel：單一工作表，逐日一列。 */
    private byte[] buildExcel(int year, List<DayRow> days) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(boldFont);
            head.setAlignment(HorizontalAlignment.CENTER);

            Sheet sheet = wb.createSheet("交易日曆 " + year);
            int r = 0;

            Row title = sheet.createRow(r++);
            Cell tc = title.createCell(0);
            tc.setCellValue(year + " 年交易日曆（產生時間 " + LocalDateTime.now(TW_ZONE).format(TS_FMT) + "）");
            tc.setCellStyle(head);

            String[] headers = {"日期", "星期", "台股交易日", "美股交易日", "英股交易日",
                    "台股假日", "美股假日", "英股假日"};
            Row hr = sheet.createRow(r++);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(head);
            }

            for (DayRow day : days) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(day.date());
                row.createCell(1).setCellValue(day.weekday());
                row.createCell(2).setCellValue(day.tw() ? "○" : "休");
                row.createCell(3).setCellValue(day.us() ? "○" : "休");
                row.createCell(4).setCellValue(day.uk() ? "○" : "休");
                row.createCell(5).setCellValue(day.twHoliday() != null ? day.twHoliday() : "");
                row.createCell(6).setCellValue(day.usHoliday() != null ? day.usHoliday() : "");
                row.createCell(7).setCellValue(day.ukHoliday() != null ? day.ukHoliday() : "");
            }

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ===== 資料夾瀏覽（唯讀，沿用 Requirement 34 樹狀選擇器契約）=====

    /**
     * 唯讀列出基底（家目錄）下指定相對子路徑的「子目錄」清單，供前端檔案總管式樹狀選擇器逐層懶載入；
     * 僅列目錄名稱、隱藏 dotfiles、依名稱排序，不讀檔案內容、不變更檔案系統。以 normalize {@code startsWith(base)} 防跳脫。
     */
    public TradingCalendarExportDto.BrowseResponse browse(String subpath) {
        String sub = normalizeSubpath(subpath);
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path target = base.resolve(sub).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("瀏覽路徑不可跳脫基底目錄：" + subpath);
        }

        final String parent = sub;
        List<TradingCalendarExportDto.DirEntry> dirs = new ArrayList<>();
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().startsWith(".")) // 隱藏 dotfiles
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase()))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            String childPath = parent.isEmpty() ? name : parent + "/" + name;
                            dirs.add(TradingCalendarExportDto.DirEntry.builder().name(name).path(childPath).build());
                        });
            } catch (IOException e) {
                throw new RuntimeException("讀取目錄失敗：" + e.getMessage(), e);
            }
        }
        return TradingCalendarExportDto.BrowseResponse.builder()
                .baseDir(baseDir)
                .subpath(sub)
                .absolutePath(target.toString())
                .directories(dirs)
                .build();
    }

    // ===== 供排程設定 PUT 前置驗證（Task 185）=====

    /** 驗證子路徑正規化後不跳脫基底目錄（非法丟 IllegalArgumentException → 400）；回正規化後子路徑。 */
    public String requireValidSubpath(String subpath) {
        String sub = normalizeSubpath(subpath);
        resolveDir(sub);
        return sub;
    }

    // ===== 輔助 =====

    /** trim + 去頭尾斜線；空字串保留（＝家目錄根）。 */
    private static String normalizeSubpath(String subpath) {
        String sub = subpath == null ? "" : subpath.trim();
        while (sub.startsWith("/")) sub = sub.substring(1);
        while (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);
        return sub;
    }

    /** 基底 resolve 子路徑並驗證仍在基底內（拒 `..`／絕對路徑跳脫）。 */
    private Path resolveDir(String subpath) {
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path target = base.resolve(subpath).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath);
        }
        return target;
    }


    /** 單日交易日曆列；鍵名（tw/us/uk + *Holiday）與 TradingCalendarView 前端日格模型一致。 */
    public record DayRow(
            String date,
            String weekday,
            boolean tw,
            boolean us,
            boolean uk,
            String twHoliday,
            String usHoliday,
            String ukHoliday
    ) {}
}
