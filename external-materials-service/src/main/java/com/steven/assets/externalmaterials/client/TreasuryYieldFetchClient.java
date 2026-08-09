package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 美國財政部每日殖利率曲線抓取器（Requirement 58 / Task 275）。
 *
 * <p>年度 Treasury CSV 是 primary；只有 CSV 連線／格式／空資料失敗，或個別官方 curve 不完整時，
 * 才抓 Yahoo {@code ^IRX/^FVX/^TNX/^TYX} 四條完整歷史並以「整批」fallback。current-year CSV
 * 不可用時會先嘗試 Treasury XML current feed；Yahoo 仍只補官方沒有完整 curve 的日期。</p>
 */
@Slf4j
@Component
public class TreasuryYieldFetchClient {

    public static final String PROVIDER_OFFICIAL = "US_TREASURY";
    public static final String PROVIDER_YAHOO = "YAHOO_PROXY";
    public static final String BASIS_HISTORIC = "CONSERVATIVE_NEXT_MIDNIGHT_ET";
    public static final String BASIS_CURRENT = "OBSERVED_CURRENT_FETCH";
    public static final String BASIS_PROXY = "PROXY_CLOSE_CONSERVATIVE";

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final List<String> TENOR_ORDER = List.of("M3", "Y5", "Y10", "Y30");
    private static final Map<String, String> OFFICIAL_CSV_HEADERS = Map.of(
            "M3", "3 Mo", "Y5", "5 Yr", "Y10", "10 Yr", "Y30", "30 Yr");
    private static final Map<String, String> XML_FIELDS = Map.of(
            "M3", "BC_3MONTH", "Y5", "BC_5YEAR", "Y10", "BC_10YEAR", "Y30", "BC_30YEAR");
    private static final Map<String, String> YAHOO_SYMBOLS = Map.of(
            "M3", "^IRX", "Y5", "^FVX", "Y10", "^TNX", "Y30", "^TYX");
    private static final String OFFICIAL_CSV_TEMPLATE =
            "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/"
                    + "daily-treasury-rates.csv/%d/all?type=daily_treasury_yield_curve"
                    + "&field_tdr_date_value=%d&page&_format=csv";
    private static final String OFFICIAL_XML_URL =
            "https://home.treasury.gov/sites/default/files/interest-rates/yield.xml";
    private static final String YAHOO_CHART_BASE = "https://query2.finance.yahoo.com/v8/finance/chart/";
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US);

    private final ObjectMapper mapper;
    private final Clock clock;
    private final TextTransport transport;

    public TreasuryYieldFetchClient(ObjectMapper mapper) {
        this(mapper, Clock.systemUTC(), new JdkTextTransport());
    }

    TreasuryYieldFetchClient(ObjectMapper mapper, Clock clock, TextTransport transport) {
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.transport = Objects.requireNonNull(transport);
    }

    /** 抓指定年度；回傳的每一筆都是不可變、單一 provider 的 curve batch。 */
    public List<CurveBatch> fetchYear(int year) {
        int currentYear = LocalDate.now(clock.withZone(NEW_YORK)).getYear();
        if (year < 1990 || year > currentYear) {
            throw new IllegalArgumentException("Treasury year 必須介於 1990 與 " + currentYear + "：" + year);
        }
        Instant fetchedAt = clock.instant();
        String csvUrl = officialCsvUrl(year);
        List<CurveBatch> official = List.of();
        boolean csvUnavailable = false;
        try {
            official = parseOfficialCsv(transport.get(csvUrl), year, csvUrl, fetchedAt);
            if (official.isEmpty()) {
                csvUnavailable = true;
                log.warn("Treasury 年度 CSV {} 無可解析 curve，啟用 secondary/fallback", year);
            }
        } catch (Exception e) {
            csvUnavailable = true;
            log.warn("Treasury 年度 CSV {} 失敗，啟用 secondary/fallback：{}", year, e.getMessage());
        }

        if (csvUnavailable && year == currentYear) {
            try {
                List<CurveBatch> xml = parseOfficialXml(
                        transport.get(OFFICIAL_XML_URL), year, OFFICIAL_XML_URL, fetchedAt);
                if (!xml.isEmpty()) official = xml;
            } catch (Exception e) {
                log.warn("Treasury XML current feed {} 失敗：{}", year, e.getMessage());
            }
        }

        Set<LocalDate> completeOfficialDates = new LinkedHashSet<>();
        Set<LocalDate> incompleteOfficialDates = new LinkedHashSet<>();
        for (CurveBatch batch : official) {
            (batch.complete() ? completeOfficialDates : incompleteOfficialDates).add(batch.curveDate());
        }

        boolean needsYahoo = csvUnavailable || !incompleteOfficialDates.isEmpty();
        List<CurveBatch> fallback = List.of();
        if (needsYahoo) {
            try {
                // CSV 整體不可用時，Yahoo 補該年度所有共同日期；個別 partial 時只補那幾天。
                Set<LocalDate> targetDates = csvUnavailable ? null : incompleteOfficialDates;
                fallback = fetchYahooYear(year, targetDates, fetchedAt).stream()
                        .filter(batch -> !completeOfficialDates.contains(batch.curveDate()))
                        .toList();
            } catch (Exception e) {
                log.warn("Treasury Yahoo 四 ticker fallback {} 失敗：{}", year, e.getMessage());
            }
        }

        List<CurveBatch> result = new ArrayList<>(official.size() + fallback.size());
        result.addAll(official);
        result.addAll(fallback);
        result.sort(Comparator.comparing(CurveBatch::curveDate)
                .thenComparing(batch -> PROVIDER_OFFICIAL.equals(batch.provider()) ? 0 : 1));
        return List.copyOf(result);
    }

    List<CurveBatch> parseOfficialCsv(String body, int year, String sourceUrl, Instant fetchedAt) {
        if (body == null || body.isBlank()) return List.of();
        String[] lines = body.replace("\r", "").split("\n");
        int headerLine = -1;
        List<String> header = List.of();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                headerLine = i;
                header = parseCsvLine(lines[i]);
                break;
            }
        }
        if (headerLine < 0) return List.of();
        Map<String, Integer> indexes = headerIndexes(header);
        Integer dateIndex = indexes.get("date");
        if (dateIndex == null || TENOR_ORDER.stream().anyMatch(t -> !indexes.containsKey(t))) {
            throw new IllegalArgumentException("Treasury CSV 缺少 Date/3 Mo/5 Yr/10 Yr/30 Yr 欄位");
        }

        List<CurveBatch> batches = new ArrayList<>();
        for (int i = headerLine + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> cells = parseCsvLine(lines[i]);
            LocalDate curveDate = parseDate(cell(cells, dateIndex));
            if (curveDate == null || curveDate.getYear() != year) continue;
            Map<String, BigDecimal> values = new LinkedHashMap<>();
            for (String tenor : TENOR_ORDER) {
                BigDecimal value = parseYield(cell(cells, indexes.get(tenor)));
                if (value != null) values.put(tenor, value);
            }
            batches.add(officialBatch(curveDate, sourceUrl, fetchedAt, values));
        }
        return List.copyOf(batches);
    }

    List<CurveBatch> parseOfficialXml(String body, int year, String sourceUrl, Instant fetchedAt) {
        if (body == null || body.isBlank()) return List.of();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            NodeList properties = document.getElementsByTagNameNS("*", "properties");
            List<CurveBatch> batches = new ArrayList<>();
            for (int i = 0; i < properties.getLength(); i++) {
                Element element = (Element) properties.item(i);
                Map<String, String> fields = childTextByLocalName(element);
                LocalDate curveDate = parseIsoDate(fields.get("NEW_DATE"));
                if (curveDate == null) curveDate = parseIsoDate(fields.get("Date"));
                if (curveDate == null || curveDate.getYear() != year) continue;
                Map<String, BigDecimal> values = new LinkedHashMap<>();
                for (String tenor : TENOR_ORDER) {
                    BigDecimal value = parseYield(fields.get(XML_FIELDS.get(tenor)));
                    if (value != null) values.put(tenor, value);
                }
                batches.add(officialBatch(curveDate, sourceUrl, fetchedAt, values));
            }
            return List.copyOf(batches);
        } catch (Exception e) {
            throw new IllegalArgumentException("Treasury XML 格式錯誤", e);
        }
    }

    private List<CurveBatch> fetchYahooYear(int year, Set<LocalDate> targetDates, Instant fetchedAt)
            throws Exception {
        Map<String, Map<LocalDate, BigDecimal>> byTenor = new LinkedHashMap<>();
        Map<String, String> sourceUrls = new LinkedHashMap<>();
        for (String tenor : TENOR_ORDER) {
            String url = yahooUrl(YAHOO_SYMBOLS.get(tenor), year);
            sourceUrls.put(tenor, url);
            Map<LocalDate, BigDecimal> values = parseYahooSeries(transport.get(url), year);
            if (values.isEmpty()) return List.of();
            byTenor.put(tenor, values);
        }
        Set<LocalDate> commonDates = new TreeSet<>(byTenor.get(TENOR_ORDER.getFirst()).keySet());
        for (String tenor : TENOR_ORDER.subList(1, TENOR_ORDER.size())) {
            commonDates.retainAll(byTenor.get(tenor).keySet());
        }
        if (targetDates != null) commonDates.retainAll(targetDates);

        List<CurveBatch> batches = new ArrayList<>();
        for (LocalDate date : commonDates) {
            List<TenorQuote> quotes = new ArrayList<>(4);
            Map<String, BigDecimal> hashValues = new LinkedHashMap<>();
            for (String tenor : TENOR_ORDER) {
                BigDecimal value = byTenor.get(tenor).get(date);
                quotes.add(new TenorQuote(tenor, value, sourceUrls.get(tenor)));
                hashValues.put(tenor, value);
            }
            Instant proxyBoundary = date.atTime(18, 0).atZone(NEW_YORK).toInstant();
            Instant availableAt = fetchedAt.isAfter(proxyBoundary) ? fetchedAt : proxyBoundary;
            batches.add(new CurveBatch(date, PROVIDER_YAHOO, YAHOO_CHART_BASE,
                    availableAt, BASIS_PROXY, fetchedAt, true, canonicalHash(hashValues), quotes));
        }
        return List.copyOf(batches);
    }

    Map<LocalDate, BigDecimal> parseYahooSeries(String body, int year) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode result = root.path("chart").path("result").path(0);
        JsonNode timestamps = result.path("timestamp");
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        if (!timestamps.isArray() || !closes.isArray()) return Map.of();
        ZoneId zone;
        try {
            zone = ZoneId.of(result.path("meta").path("exchangeTimezoneName")
                    .asText("America/New_York"));
        } catch (Exception ignored) {
            zone = NEW_YORK;
        }
        Map<LocalDate, BigDecimal> values = new LinkedHashMap<>();
        int size = Math.min(timestamps.size(), closes.size());
        for (int i = 0; i < size; i++) {
            JsonNode close = closes.get(i);
            if (close == null || close.isNull() || !close.isNumber()) continue;
            LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong()).atZone(zone).toLocalDate();
            if (date.getYear() != year) continue;
            BigDecimal value = normalizeYield(close.decimalValue());
            if (value != null) values.put(date, value);
        }
        return Map.copyOf(values);
    }

    private CurveBatch officialBatch(LocalDate date, String sourceUrl, Instant fetchedAt,
                                     Map<String, BigDecimal> values) {
        List<TenorQuote> quotes = TENOR_ORDER.stream()
                .filter(values::containsKey)
                .map(tenor -> new TenorQuote(tenor, values.get(tenor), sourceUrl))
                .toList();
        boolean complete = quotes.size() == TENOR_ORDER.size();
        Instant boundary = date.plusDays(1).atStartOfDay(NEW_YORK).toInstant();
        LocalDate fetchedDateEt = fetchedAt.atZone(NEW_YORK).toLocalDate();
        boolean currentFetch = !date.isBefore(fetchedDateEt.minusDays(1));
        Instant availableAt = currentFetch && fetchedAt.isAfter(boundary) ? fetchedAt : boundary;
        String basis = currentFetch ? BASIS_CURRENT : BASIS_HISTORIC;
        return new CurveBatch(date, PROVIDER_OFFICIAL, sourceUrl, availableAt, basis,
                fetchedAt, complete, canonicalHash(values), quotes);
    }

    static String canonicalHash(Map<String, BigDecimal> values) {
        StringBuilder canonical = new StringBuilder();
        for (String tenor : TENOR_ORDER) {
            if (!canonical.isEmpty()) canonical.append('|');
            BigDecimal value = values.get(tenor);
            canonical.append(tenor).append('=')
                    .append(value == null ? "MISSING" : value.stripTrailingZeros().toPlainString());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, Integer> headerIndexes(List<String> header) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).trim();
            if ("Date".equalsIgnoreCase(name)) indexes.put("date", i);
            for (Map.Entry<String, String> entry : OFFICIAL_CSV_HEADERS.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(name)) indexes.put(entry.getKey(), i);
            }
        }
        return indexes;
    }

    static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    private static String cell(List<String> cells, Integer index) {
        return index == null || index < 0 || index >= cells.size() ? null : cells.get(index);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim(), US_DATE);
        } catch (DateTimeParseException e) {
            return parseIsoDate(value);
        }
    }

    private static LocalDate parseIsoDate(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() >= 10) trimmed = trimmed.substring(0, 10);
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal parseYield(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return normalizeYield(new BigDecimal(value.trim().replace(",", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal normalizeYield(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) return null;
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static Map<String, String> childTextByLocalName(Element element) {
        Map<String, String> values = new HashMap<>();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            values.put(name, child.getTextContent());
        }
        return values;
    }

    private static String officialCsvUrl(int year) {
        return OFFICIAL_CSV_TEMPLATE.formatted(year, year);
    }

    private static String yahooUrl(String symbol, int year) {
        long period1 = LocalDate.of(year, 1, 1).atStartOfDay(NEW_YORK).toEpochSecond();
        long period2 = LocalDate.of(year + 1, 1, 1).atStartOfDay(NEW_YORK).toEpochSecond();
        return YAHOO_CHART_BASE + symbol.replace("^", "%5E")
                + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d&events=history";
    }

    /** 完整 curve batch；tenors 與其內 record 均 immutable。 */
    public record CurveBatch(LocalDate curveDate, String provider, String sourceUrl,
                             Instant availableAt, String availabilityBasis, Instant fetchedAt,
                             boolean complete, String contentHash, List<TenorQuote> tenors) {
        public CurveBatch {
            tenors = tenors == null ? List.of() : List.copyOf(tenors);
        }
    }

    public record TenorQuote(String tenor, BigDecimal yieldPercent, String sourceUrl) {}

    @FunctionalInterface
    interface TextTransport {
        String get(String url) throws Exception;
    }

    private static final class JdkTextTransport implements TextTransport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public String get(String url) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "asset-management/1.0")
                    .header("Accept", "text/csv, application/xml, application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
            }
            return response.body();
        }
    }
}
