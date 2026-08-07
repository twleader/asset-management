package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.service.StockSourceQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 以指定交易日抓取 TWSE／TPEx 官方全市場日收盤。 */
@Slf4j
@Component
public class TwOfficialCloseClient {

    static final String TWSE_URL =
            "https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date=%s&type=ALLBUT0999&response=json";
    static final String TPEX_URL =
            "https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final CurlExecutor curl;

    public TwOfficialCloseClient() {
        this(new ProcessCurlExecutor());
    }

    TwOfficialCloseClient(CurlExecutor curl) {
        this.curl = curl;
    }

    public record OfficialClose(
            String code,
            String name,
            LocalDate tradingDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            Long volume,
            String source
    ) {}

    public record OfficialCloseBatch(
            Map<String, OfficialClose> rows,
            List<String> sourceFailures
    ) {
        public OfficialCloseBatch {
            rows = Collections.unmodifiableMap(new LinkedHashMap<>(rows));
            sourceFailures = List.copyOf(sourceFailures);
        }
    }

    public OfficialCloseBatch fetch(LocalDate targetDate) {
        LinkedHashMap<String, OfficialClose> rows = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        try {
            merge(rows, parseTwse(curl.get(TWSE_URL.formatted(targetDate.toString().replace("-", ""))), targetDate));
        } catch (Exception e) {
            failures.add(StockSourceQuery.TWSE_MI_INDEX + ": " + concise(e));
            log.warn("TWSE 官方收盤抓取失敗 {}: {}", targetDate, e.getMessage());
        }
        try {
            merge(rows, parseTpex(curl.get(TPEX_URL), targetDate));
        } catch (Exception e) {
            failures.add(StockSourceQuery.TPEX_DAILY_CLOSE + ": " + concise(e));
            log.warn("TPEx 官方收盤抓取失敗 {}: {}", targetDate, e.getMessage());
        }
        return new OfficialCloseBatch(rows, failures);
    }

    private void merge(Map<String, OfficialClose> target, Map<String, OfficialClose> incoming) {
        for (Map.Entry<String, OfficialClose> e : incoming.entrySet()) {
            if (target.containsKey(e.getKey())) {
                log.warn("官方收盤異常重碼 {}，以後讀來源 {} 覆寫", e.getKey(), e.getValue().source());
            }
            target.put(e.getKey(), e.getValue());
        }
    }

    Map<String, OfficialClose> parseTwse(String json, LocalDate targetDate) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        if (!"OK".equals(root.path("stat").asText())) {
            throw new IllegalStateException("stat=" + root.path("stat").asText());
        }
        String expected = targetDate.toString().replace("-", "");
        if (!expected.equals(root.path("date").asText())) {
            throw new IllegalStateException("response date=" + root.path("date").asText());
        }

        JsonNode selected = null;
        for (JsonNode table : root.path("tables")) {
            JsonNode fields = table.path("fields");
            if (indexOf(fields, "證券代號") >= 0 && indexOf(fields, "收盤價") >= 0) {
                selected = table;
                break;
            }
        }
        if (selected == null) throw new IllegalStateException("找不到每日收盤行情 table");

        JsonNode fields = selected.path("fields");
        int codeIx = indexOf(fields, "證券代號");
        int nameIx = indexOf(fields, "證券名稱");
        int volumeIx = indexOf(fields, "成交股數");
        int openIx = indexOf(fields, "開盤價");
        int highIx = indexOf(fields, "最高價");
        int lowIx = indexOf(fields, "最低價");
        int closeIx = indexOf(fields, "收盤價");

        LinkedHashMap<String, OfficialClose> result = new LinkedHashMap<>();
        for (JsonNode row : selected.path("data")) {
            String code = cellText(row, codeIx);
            BigDecimal close = decimal(cellText(row, closeIx));
            if (code == null || close == null || close.signum() <= 0) continue;
            result.put(code, new OfficialClose(
                    code,
                    cellText(row, nameIx),
                    targetDate,
                    decimal(cellText(row, openIx)),
                    decimal(cellText(row, highIx)),
                    decimal(cellText(row, lowIx)),
                    close,
                    longValue(cellText(row, volumeIx)),
                    StockSourceQuery.TWSE_MI_INDEX));
        }
        return result;
    }

    Map<String, OfficialClose> parseTpex(String json, LocalDate targetDate) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        if (!root.isArray()) throw new IllegalStateException("TPEx root 不是 array");
        LinkedHashMap<String, OfficialClose> result = new LinkedHashMap<>();
        for (JsonNode row : root) {
            LocalDate rowDate = rocDate(text(row, "Date"));
            if (!targetDate.equals(rowDate)) continue;
            String code = text(row, "SecuritiesCompanyCode");
            BigDecimal close = decimal(text(row, "Close"));
            if (code == null || close == null || close.signum() <= 0) continue;
            result.put(code, new OfficialClose(
                    code,
                    text(row, "CompanyName"),
                    targetDate,
                    decimal(text(row, "Open")),
                    decimal(text(row, "High")),
                    decimal(text(row, "Low")),
                    close,
                    longValue(text(row, "TradingShares")),
                    StockSourceQuery.TPEX_DAILY_CLOSE));
        }
        return result;
    }

    private static int indexOf(JsonNode fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (name.equals(fields.get(i).asText().trim())) return i;
        }
        return -1;
    }

    private static String cellText(JsonNode row, int index) {
        if (index < 0 || !row.isArray() || index >= row.size() || row.get(index).isNull()) return null;
        return clean(row.get(index).asText());
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : clean(value.asText());
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.equals("--") || value.equals("---")) return null;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longValue(String value) {
        BigDecimal number = decimal(value);
        if (number == null) return null;
        try {
            return number.longValueExact();
        } catch (ArithmeticException e) {
            return number.longValue();
        }
    }

    private static LocalDate rocDate(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("[^0-9]", "");
        try {
            if (digits.length() == 7) {
                int year = Integer.parseInt(digits.substring(0, 3)) + 1911;
                return LocalDate.of(year,
                        Integer.parseInt(digits.substring(3, 5)),
                        Integer.parseInt(digits.substring(5, 7)));
            }
            if (digits.length() == 8) {
                return LocalDate.of(
                        Integer.parseInt(digits.substring(0, 4)),
                        Integer.parseInt(digits.substring(4, 6)),
                        Integer.parseInt(digits.substring(6, 8)));
            }
        } catch (RuntimeException ignored) {
            // malformed date is a row-level miss
        }
        return null;
    }

    private static String concise(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    interface CurlExecutor {
        String get(String url) throws Exception;
    }

    private static final class ProcessCurlExecutor implements CurlExecutor {
        @Override
        public String get(String url) throws Exception {
            Process process = new ProcessBuilder(
                    "curl", "--fail", "--silent", "--show-error", "--max-time", "25",
                    "-A", "Mozilla/5.0", url)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) throw new IllegalStateException("curl exit=" + exit + " " + output.trim());
            return output;
        }
    }
}
