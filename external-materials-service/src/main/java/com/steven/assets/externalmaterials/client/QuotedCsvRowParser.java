package com.steven.assets.externalmaterials.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 單列 RFC-4180 風格 CSV parser。支援 quoted comma 與雙引號 escaping，並拒絕未閉合或
 * 出現在欄位中段的 malformed quote；刻意不處理跨列 quoted field，官方來源也不使用該格式。
 */
final class QuotedCsvRowParser {

    private QuotedCsvRowParser() {}

    static List<String> parse(String line) {
        if (line == null) throw new IllegalArgumentException("CSV row 不可為 null");

        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch != '"') {
                    cell.append(ch);
                    continue;
                }
                if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                    closedQuote = true;
                }
                continue;
            }

            if (closedQuote) {
                if (ch != ',') {
                    throw new IllegalArgumentException("CSV closing quote 後只能接逗號或列尾");
                }
                cells.add(cell.toString().trim());
                cell.setLength(0);
                closedQuote = false;
                continue;
            }

            if (ch == ',') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else if (ch == '"') {
                if (cell.length() != 0) {
                    throw new IllegalArgumentException("CSV quote 只能出現在欄位開頭");
                }
                quoted = true;
            } else {
                cell.append(ch);
            }
        }

        if (quoted) throw new IllegalArgumentException("CSV quoted field 未閉合");
        cells.add(cell.toString().trim());
        return List.copyOf(cells);
    }
}
