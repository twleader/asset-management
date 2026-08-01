package com.steven.assets.service.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 零回歸比對工具（Requirement 55 / Task 270、271）。
 *
 * <p>基準是 origin/main 的臨時 worktree 以相同 fixture 產出的 golden xlsx。
 * 比對逐列逐格：型別、值、{@code dataFormat}、粗體、字級，外加 {@code getLastCellNum()}
 * ——最後一項是「BLANK 格 vs 完全不建格」唯一分辨得出來的地方。
 */
final class GoldenWorkbooks {

    private GoldenWorkbooks() {}

    static Workbook golden(String name) throws Exception {
        try (InputStream in = GoldenWorkbooks.class.getResourceAsStream("/golden/" + name + ".xlsx")) {
            assertThat(in).as("golden file /golden/%s.xlsx 必須存在", name).isNotNull();
            return new XSSFWorkbook(in);
        }
    }

    static Workbook read(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    /** 逐列逐格比對；skipCells 內的「分頁名:列:欄」座標只驗存在與型別，不比值（牆鐘時間戳用）。 */
    static void assertSame(Workbook expected, Workbook actual, Set<String> skipValueAt) {
        assertThat(actual.getNumberOfSheets()).isEqualTo(expected.getNumberOfSheets());
        for (int si = 0; si < expected.getNumberOfSheets(); si++) {
            Sheet e = expected.getSheetAt(si);
            Sheet a = actual.getSheetAt(si);
            assertThat(a.getSheetName()).as("第 %d 張分頁名", si).isEqualTo(e.getSheetName());
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
                    String where = e.getSheetName() + " (" + ri + "," + ci + ")";
                    if (ec == null) {
                        assertThat(ac).as("%s 該格應不存在", where).isNull();
                        continue;
                    }
                    assertThat(ac).as("%s 該格應存在", where).isNotNull();
                    assertThat(ac.getCellType()).as("%s 型別", where).isEqualTo(ec.getCellType());
                    boolean skip = skipValueAt.contains(e.getSheetName() + ":" + ri + ":" + ci);
                    if (!skip && ec.getCellType() == CellType.STRING) {
                        assertThat(ac.getStringCellValue()).as("%s 值", where).isEqualTo(ec.getStringCellValue());
                    } else if (!skip && ec.getCellType() == CellType.NUMERIC) {
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

    static void assertSame(Workbook expected, Workbook actual) {
        assertSame(expected, actual, Set.of());
    }
}
