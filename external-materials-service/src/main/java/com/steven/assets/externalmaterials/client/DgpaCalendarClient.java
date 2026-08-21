package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.service.DgpaCalendarAuthority;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * data.gov.tw 資料集 14718 的 DGPA 完整辦公日曆 adapter。下載 URL 一律重新 allowlist，
 * 並只在整年 CSV identity/schema/coverage 全部通過後回傳暫行台股休市 map。
 */
@Slf4j
@Component
public class DgpaCalendarClient implements DgpaCalendarAuthority {

    static final URI METADATA_URI = URI.create("https://data.gov.tw/api/v2/rest/dataset/14718");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final List<String> HEADER = List.of("西元日期", "星期", "是否放假", "備註");
    private static final String WEEKDAYS = "日一二三四五六";
    private static final String DGPA_FALLBACK_LABEL = "行政院人事行政總處放假日";
    private static final String PRE_SPRING_LABEL = "農曆春節前市場無交易";

    private final ObjectMapper mapper;
    private final HttpTransport transport;

    @Autowired
    public DgpaCalendarClient(ObjectMapper mapper) {
        this(mapper, new JdkHttpTransport(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()));
    }

    DgpaCalendarClient(ObjectMapper mapper, HttpTransport transport) {
        this.mapper = mapper;
        this.transport = transport;
    }

    @Override
    public Optional<Map<String, String>> fetchHolidays(int year) {
        try {
            if (year < 1912 || year > 9999) return Optional.empty();
            HttpPayload metadataResponse = transport.get(METADATA_URI);
            if (metadataResponse.statusCode() != 200) return Optional.empty();
            String metadataBody = decodeStrict(metadataResponse.body(), StandardCharsets.UTF_8);
            Resource resource = selectResource(mapper.readTree(metadataBody), year).orElse(null);
            if (resource == null) return Optional.empty();

            Charset charset = declaredCharset(resource.encoding()).orElse(null);
            if (charset == null || !isAllowedDownloadUri(resource.downloadUri())) return Optional.empty();

            HttpPayload csvResponse = transport.get(resource.downloadUri());
            if (csvResponse.statusCode() != 200) return Optional.empty();
            Map<String, String> holidays = parseCalendar(year, decodeStrict(csvResponse.body(), charset));
            log.info("DGPA provisional calendar fetched for {}: {} holiday entries", year, holidays.size());
            return Optional.of(holidays);
        } catch (Exception e) {
            log.warn("DGPA calendar unavailable for {}: {}", year, concise(e));
            return Optional.empty();
        }
    }

    Optional<Resource> selectResource(JsonNode root, int year) {
        JsonNode distributions = root.path("result").path("distribution");
        if (!distributions.isArray()) return Optional.empty();

        int rocYear = year - 1911;
        String base = rocYear + "年中華民國政府行政機關辦公日曆表";
        Pattern descriptionPattern = Pattern.compile("^" + Pattern.quote(base) + "(?:\\(([^()]*)\\))?$");
        List<ResourceCandidate> candidates = new ArrayList<>();
        for (JsonNode node : distributions) {
            if (!"CSV".equalsIgnoreCase(node.path("resourceFormat").asText("").trim())) continue;
            String description = node.path("resourceDescription").asText("").trim();
            Matcher matcher = descriptionPattern.matcher(description);
            if (!matcher.matches() || description.contains("_Google行事曆專用")) continue;
            candidates.add(new ResourceCandidate(
                    description,
                    matcher.group(1),
                    node.path("resourceCharacterEncoding").asText("").trim(),
                    node.path("resourceDownloadUrl").asText("").trim(),
                    node.path("resourceQualityCheckTime").asText("").trim()));
        }
        if (candidates.isEmpty()) return Optional.empty();

        List<ResourceCandidate> revisions = candidates.stream()
                .filter(candidate -> candidate.revision() != null)
                .toList();
        List<ResourceCandidate> selectedVersion;
        if (revisions.isEmpty()) {
            selectedVersion = candidates.stream()
                    .filter(candidate -> candidate.revision() == null)
                    .toList();
        } else {
            Map<String, List<ResourceCandidate>> byRevision = new LinkedHashMap<>();
            revisions.forEach(candidate -> byRevision
                    .computeIfAbsent(candidate.revision(), ignored -> new ArrayList<>())
                    .add(candidate));
            if (byRevision.size() == 1) {
                selectedVersion = byRevision.values().iterator().next();
            } else {
                Pattern datedRevision = Pattern.compile("^" + rocYear + "(\\d{4})更新$");
                Map<String, LocalDate> revisionDates = new HashMap<>();
                for (String revision : byRevision.keySet()) {
                    Matcher matcher = datedRevision.matcher(revision);
                    if (!matcher.matches()) return Optional.empty();
                    String monthDay = matcher.group(1);
                    try {
                        revisionDates.put(revision, LocalDate.of(year,
                                Integer.parseInt(monthDay.substring(0, 2)),
                                Integer.parseInt(monthDay.substring(2, 4))));
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                }
                String latest = revisionDates.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);
                if (latest == null) return Optional.empty();
                selectedVersion = byRevision.get(latest);
            }
        }

        ResourceCandidate selected = selectDuplicate(selectedVersion).orElse(null);
        if (selected == null) return Optional.empty();
        try {
            return Optional.of(new Resource(selected.encoding(), URI.create(selected.downloadUrl())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<ResourceCandidate> selectDuplicate(List<ResourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        if (candidates.size() == 1) return Optional.of(candidates.get(0));

        Map<Instant, List<ResourceCandidate>> byQualityTime = new HashMap<>();
        for (ResourceCandidate candidate : candidates) {
            Instant time = parseQualityTime(candidate.qualityCheckTime()).orElse(null);
            if (time == null) return Optional.empty();
            byQualityTime.computeIfAbsent(time, ignored -> new ArrayList<>()).add(candidate);
        }
        Instant latest = byQualityTime.keySet().stream().max(Comparator.naturalOrder()).orElse(null);
        if (latest == null || byQualityTime.get(latest).size() != 1) return Optional.empty();
        return Optional.of(byQualityTime.get(latest).get(0));
    }

    private static Optional<Instant> parseQualityTime(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(value));
        } catch (RuntimeException ignored) {
            // try official metadata formats below
        }
        try {
            return Optional.of(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
        } catch (RuntimeException ignored) {
            // continue
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm:ss"))) {
            try {
                return Optional.of(LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC));
            } catch (RuntimeException ignored) {
                // continue
            }
        }
        return Optional.empty();
    }

    private static Optional<Charset> declaredCharset(String encoding) {
        String normalized = encoding == null ? "" : encoding.trim();
        if ("UTF-8".equals(normalized)) return Optional.of(StandardCharsets.UTF_8);
        if ("BIG5".equals(normalized)) return Optional.of(Charset.forName("Big5"));
        return Optional.empty();
    }

    static boolean isAllowedDownloadUri(URI uri) {
        if (uri == null
                || !"https".equals(uri.getScheme())
                || !"www.dgpa.gov.tw".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !"/FileConversion".equals(uri.getRawPath())
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getRawQuery() == null) {
            return false;
        }

        int filenameCount = 0;
        String filename = null;
        for (String part : uri.getRawQuery().split("&", -1)) {
            int equals = part.indexOf('=');
            String rawName = equals < 0 ? part : part.substring(0, equals);
            String rawValue = equals < 0 ? "" : part.substring(equals + 1);
            try {
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                if ("filename".equals(name)) {
                    filenameCount++;
                    filename = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return filenameCount == 1
                && filename != null
                && filename.endsWith(".csv");
    }

    Map<String, String> parseCalendar(int year, String csv) {
        if (csv == null) throw new IllegalArgumentException("DGPA CSV body 不可為 null");
        List<String> lines = csv.lines().toList();
        if (lines.size() != Year.of(year).length() + 1) {
            throw new IllegalArgumentException("DGPA CSV 必須完整涵蓋全年");
        }

        List<String> header = new ArrayList<>(QuotedCsvRowParser.parse(lines.get(0)));
        if (!header.isEmpty() && header.get(0).startsWith("\uFEFF")) {
            header.set(0, header.get(0).substring(1));
        }
        if (!HEADER.equals(header)) throw new IllegalArgumentException("DGPA CSV header 不符");

        Map<LocalDate, CalendarRow> rows = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = QuotedCsvRowParser.parse(lines.get(i));
            if (cells.size() != 4) throw new IllegalArgumentException("DGPA CSV row 欄數不符");
            LocalDate date = LocalDate.parse(cells.get(0), DATE_FORMAT);
            if (date.getYear() != year || rows.containsKey(date)) {
                throw new IllegalArgumentException("DGPA CSV 日期錯年或重複");
            }
            String expectedWeekday = String.valueOf(WEEKDAYS.charAt(date.getDayOfWeek().getValue() % 7));
            if (!expectedWeekday.equals(cells.get(1))) {
                throw new IllegalArgumentException("DGPA CSV 星期不符");
            }
            String flag = cells.get(2);
            if (!"0".equals(flag) && !"2".equals(flag)) {
                throw new IllegalArgumentException("DGPA CSV 是否放假旗標不符");
            }
            rows.put(date, new CalendarRow(date, flag, cells.get(3)));
        }

        LocalDate expected = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        while (!expected.isAfter(end)) {
            if (!rows.containsKey(expected)) throw new IllegalArgumentException("DGPA CSV 缺少日期 " + expected);
            expected = expected.plusDays(1);
        }

        List<CalendarRow> ordered = new ArrayList<>(rows.values());
        ordered.sort(Comparator.comparing(CalendarRow::date));
        Map<String, String> holidays = new LinkedHashMap<>();
        int springIndex = -1;
        for (int i = 0; i < ordered.size(); i++) {
            CalendarRow row = ordered.get(i);
            if (springIndex < 0 && row.note().contains("春節")) springIndex = i;
            if ("2".equals(row.flag()) && isWeekday(row.date())) {
                holidays.put(row.date().toString(), row.note().isBlank() ? DGPA_FALLBACK_LABEL : row.note());
            }
        }
        if (springIndex < 0) throw new IllegalArgumentException("DGPA CSV 缺少春節錨點");

        int added = 0;
        for (int i = springIndex - 1; i >= 0 && added < 2; i--) {
            CalendarRow row = ordered.get(i);
            if ("0".equals(row.flag()) && isWeekday(row.date())) {
                holidays.put(row.date().toString(), PRE_SPRING_LABEL);
                added++;
            }
        }
        if (added != 2) throw new IllegalArgumentException("DGPA CSV 春節前工作日不足兩日");
        return Collections.unmodifiableMap(new LinkedHashMap<>(holidays));
    }

    private static boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private static String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        var decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes == null ? new byte[0] : bytes));
        return chars.toString();
    }

    private static String concise(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    record Resource(String encoding, URI downloadUri) {}

    private record ResourceCandidate(
            String description,
            String revision,
            String encoding,
            String downloadUrl,
            String qualityCheckTime) {}

    private record CalendarRow(LocalDate date, String flag, String note) {}

    interface HttpTransport {
        HttpPayload get(URI uri) throws Exception;
    }

    record HttpPayload(int statusCode, byte[] body) {
        HttpPayload {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static final class JdkHttpTransport implements HttpTransport {
        private final HttpClient client;

        private JdkHttpTransport(HttpClient client) {
            this.client = client;
        }

        @Override
        public HttpPayload get(URI uri) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json,text/csv,*/*")
                    .header("User-Agent", "asset-management-calendar/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new HttpPayload(response.statusCode(), response.body());
        }
    }
}
