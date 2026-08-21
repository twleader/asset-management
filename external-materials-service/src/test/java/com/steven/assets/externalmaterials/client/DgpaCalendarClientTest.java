package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DgpaCalendarClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Charset BIG5 = Charset.forName("Big5");
    private static final URI CSV = URI.create(
            "https://www.dgpa.gov.tw/FileConversion?filename=calendar.csv&nfix=1&name=office");

    @Test
    void revisionWinsNewerOriginalQualityTimeAndSelectedBig5CalendarIsParsed() throws Exception {
        int year = 2025;
        URI original = uri("original.csv");
        URI revision = uri("revision.csv");
        String metadata = metadata(List.of(
                resource(year, null, "UTF-8", original, "2026-12-31 00:00:00"),
                resource(year, "1141020更新", "BIG5", revision, "2025-10-20 00:00:00")));
        Map<LocalDate, RowOverride> overrides = new HashMap<>();
        overrides.put(LocalDate.of(year, 9, 29), new RowOverride("2", "教師節"));
        overrides.put(LocalDate.of(year, 10, 24), new RowOverride("2", "臺灣光復暨金門古寧頭大捷紀念日"));
        overrides.put(LocalDate.of(year, 12, 25), new RowOverride("2", "行憲紀念日"));
        overrides.put(LocalDate.of(year, 2, 3), new RowOverride("2", "春節"));

        var result = client(metadata, Map.of(
                original, calendarCsv(year, Map.of(), false).getBytes(StandardCharsets.UTF_8),
                revision, calendarCsv(year, overrides, false).getBytes(BIG5)))
                .fetchHolidays(year);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).containsKeys(
                "2025-09-29", "2025-10-24", "2025-12-25");
    }

    @Test
    void utf8BomQuotedCommaEscapedQuoteAndDerived2026DaysAreSupported() throws Exception {
        int year = 2026;
        Map<LocalDate, RowOverride> overrides = new HashMap<>();
        overrides.put(LocalDate.of(year, 2, 16), new RowOverride("2", "春節"));
        overrides.put(LocalDate.of(year, 10, 9), new RowOverride("2", "國慶,紀念\"日"));
        String csv = calendarCsv(year, overrides, true);

        Map<String, String> holidays = fetch(year, "UTF-8", csv.getBytes(StandardCharsets.UTF_8))
                .orElseThrow();

        assertThat(holidays)
                .containsEntry("2026-02-12", "農曆春節前市場無交易")
                .containsEntry("2026-02-13", "農曆春節前市場無交易")
                .containsEntry("2026-10-09", "國慶,紀念\"日");
    }

    @Test
    void complete2027CalendarDerivesFebruarySecondAndThirdWithoutYearExceptionList() throws Exception {
        int year = 2027;
        Map<LocalDate, RowOverride> overrides = Map.of(
                LocalDate.of(year, 2, 4), new RowOverride("2", "小年夜"),
                LocalDate.of(year, 2, 5), new RowOverride("2", "除夕"),
                LocalDate.of(year, 2, 6), new RowOverride("2", "春節"));

        Map<String, String> holidays = fetch(year, "UTF-8",
                calendarCsv(year, overrides, false).getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(holidays)
                .containsEntry("2027-02-02", "農曆春節前市場無交易")
                .containsEntry("2027-02-03", "農曆春節前市場無交易");
    }

    @Test
    void leapYearRequiresAll366Rows() throws Exception {
        int year = 2024;
        Map<LocalDate, RowOverride> overrides = Map.of(
                LocalDate.of(year, 2, 12), new RowOverride("2", "春節"));
        String csv = calendarCsv(year, overrides, false);

        assertThat(fetch(year, "UTF-8", csv.getBytes(StandardCharsets.UTF_8))).isPresent();
        assertThat(csv.lines()).hasSize(367);
    }

    @Test
    void metadataExcludesGoogleAndRejectsAmbiguousRevisionsOrDuplicateTie() throws Exception {
        int year = 2027;
        DgpaCalendarClient selector = new DgpaCalendarClient(MAPPER, uri ->
                new DgpaCalendarClient.HttpPayload(404, new byte[0]));

        String googleOnly = metadata(List.of(Map.of(
                "resourceDescription", "116年中華民國政府行政機關辦公日曆表_Google行事曆專用",
                "resourceFormat", "CSV",
                "resourceCharacterEncoding", "UTF-8",
                "resourceDownloadUrl", CSV.toString(),
                "resourceQualityCheckTime", "2027-01-01 00:00:00")));
        assertThat(selector.selectResource(MAPPER.readTree(googleOnly), year)).isEmpty();

        String undated = metadata(List.of(
                resource(year, "修正版A", "UTF-8", uri("a.csv"), "2027-01-01 00:00:00"),
                resource(year, "修正版B", "UTF-8", uri("b.csv"), "2027-01-02 00:00:00")));
        assertThat(selector.selectResource(MAPPER.readTree(undated), year)).isEmpty();

        Map<String, String> duplicate = resource(
                year, "1160102更新", "UTF-8", uri("a.csv"), "2027-01-02 00:00:00");
        String tied = metadata(List.of(duplicate, new LinkedHashMap<>(duplicate)));
        assertThat(selector.selectResource(MAPPER.readTree(tied), year)).isEmpty();
    }

    @Test
    void identicalRevisionUsesUniqueLatestQualityCheckOnlyAsTieBreaker() throws Exception {
        int year = 2027;
        DgpaCalendarClient selector = new DgpaCalendarClient(MAPPER, uri ->
                new DgpaCalendarClient.HttpPayload(404, new byte[0]));
        URI older = uri("older.csv");
        URI newer = uri("newer.csv");
        String metadata = metadata(List.of(
                resource(year, "1160102更新", "UTF-8", older, "2027-01-02 00:00:00"),
                resource(year, "1160102更新", "UTF-8", newer, "2027-01-03 00:00:00")));

        assertThat(selector.selectResource(MAPPER.readTree(metadata), year))
                .contains(new DgpaCalendarClient.Resource("UTF-8", newer));
    }

    @Test
    void multipleDatedRevisionsSelectLatestSemanticDate() throws Exception {
        int year = 2025;
        URI older = uri("older.csv");
        URI newer = uri("newer.csv");
        DgpaCalendarClient selector = new DgpaCalendarClient(MAPPER, request ->
                new DgpaCalendarClient.HttpPayload(404, new byte[0]));
        String metadata = metadata(List.of(
                resource(year, "1140929更新", "UTF-8", older, "2026-12-31 00:00:00"),
                resource(year, "1141020更新", "BIG5", newer, "2025-01-01 00:00:00")));

        assertThat(selector.selectResource(MAPPER.readTree(metadata), year))
                .contains(new DgpaCalendarClient.Resource("BIG5", newer));
    }

    @Test
    void selectedVersionEncodingIsValidatedAfterRevisionRanking() throws Exception {
        int year = 2027;
        String metadata = metadata(List.of(
                resource(year, null, "UTF-8", uri("original.csv"), "2027-12-01 00:00:00"),
                resource(year, "1160102更新", "UTF-16", CSV, "2027-01-02 00:00:00")));
        AtomicInteger csvCalls = new AtomicInteger();
        DgpaCalendarClient client = new DgpaCalendarClient(MAPPER, uri -> {
            if (DgpaCalendarClient.METADATA_URI.equals(uri)) {
                return new DgpaCalendarClient.HttpPayload(200, metadata.getBytes(StandardCharsets.UTF_8));
            }
            csvCalls.incrementAndGet();
            return new DgpaCalendarClient.HttpPayload(200, new byte[0]);
        });

        assertThat(client.fetchHolidays(year)).isEmpty();
        assertThat(csvCalls).hasValue(0);
    }

    @Test
    void malformedNewerRevisionUrlFailsClosedInsteadOfFallingBackToValidOriginal() throws Exception {
        int year = 2027;
        Map<String, String> original = resource(
                year, null, "UTF-8", uri("original.csv"), "2027-12-01 00:00:00");
        Map<String, String> revision = resource(
                year, "1160102更新", "UTF-8", uri("placeholder.csv"), "2027-01-02 00:00:00");
        revision.put("resourceDownloadUrl", "https://www.dgpa.gov.tw/bad url");
        String metadata = metadata(List.of(original, revision));
        AtomicInteger csvCalls = new AtomicInteger();
        DgpaCalendarClient client = new DgpaCalendarClient(MAPPER, request -> {
            if (DgpaCalendarClient.METADATA_URI.equals(request)) {
                return new DgpaCalendarClient.HttpPayload(200, metadata.getBytes(StandardCharsets.UTF_8));
            }
            csvCalls.incrementAndGet();
            return new DgpaCalendarClient.HttpPayload(200, new byte[0]);
        });

        assertThat(client.fetchHolidays(year)).isEmpty();
        assertThat(csvCalls).hasValue(0);
    }

    @Test
    void downloadUriAllowlistRejectsAlternatePortDuplicateFilenameAndNonCsv() {
        assertThat(DgpaCalendarClient.isAllowedDownloadUri(CSV)).isTrue();
        assertThat(DgpaCalendarClient.isAllowedDownloadUri(URI.create(
                "https://www.dgpa.gov.tw:443/FileConversion?filename=x.csv"))).isTrue();
        assertThat(DgpaCalendarClient.isAllowedDownloadUri(URI.create(
                "https://www.dgpa.gov.tw:444/FileConversion?filename=x.csv"))).isFalse();
        assertThat(DgpaCalendarClient.isAllowedDownloadUri(URI.create(
                "https://www.dgpa.gov.tw/File%43onversion?filename=x.csv"))).isFalse();
        assertThat(DgpaCalendarClient.isAllowedDownloadUri(URI.create(
                "https://www.dgpa.gov.tw/FileConversion?filename=x.csv&filename=y.csv"))).isFalse();
        assertThat(DgpaCalendarClient.isAllowedDownloadUri(URI.create(
                "https://www.dgpa.gov.tw/FileConversion?filename=x.xlsx"))).isFalse();
        assertThat(DgpaCalendarClient.isAllowedDownloadUri(URI.create(
                "https://www.dgpa.gov.tw/FileConversion?filename=x.CSV"))).isFalse();
        assertThat(DgpaCalendarClient.isAllowedDownloadUri(URI.create(
                "https://evil.test/FileConversion?filename=x.csv"))).isFalse();
        assertThat(DgpaCalendarClient.isAllowedDownloadUri(URI.create(
                "https://user@www.dgpa.gov.tw/FileConversion?filename=x.csv#fragment"))).isFalse();
    }

    @Test
    void strictDecoderRejectsMalformedUtf8AndBig5WithoutDownloadingAnotherVersion() throws Exception {
        assertThat(fetch(2027, "UTF-8", new byte[]{(byte) 0xC3, 0x28})).isEmpty();
        assertThat(fetch(2027, "BIG5", new byte[]{(byte) 0x81})).isEmpty();
        assertThat(fetch(2027, "UTF8", new byte[0])).isEmpty();
        assertThat(fetch(2027, "BIG-5", new byte[0])).isEmpty();
    }

    @Test
    void inputRowOrderDoesNotAffectChronologicalSpringDerivationButInternalBlankIsRejected()
            throws Exception {
        int year = 2027;
        String valid = calendarCsv(year, Map.of(
                LocalDate.of(year, 2, 4), new RowOverride("2", "小年夜"),
                LocalDate.of(year, 2, 5), new RowOverride("2", "除夕"),
                LocalDate.of(year, 2, 6), new RowOverride("2", "春節")), false);
        List<String> shuffled = new ArrayList<>(valid.lines().toList());
        java.util.Collections.swap(shuffled, 2, 300);
        Map<String, String> holidays = fetch(
                year, "UTF-8", String.join("\n", shuffled).getBytes(StandardCharsets.UTF_8))
                .orElseThrow();
        assertThat(holidays).containsKeys("2027-02-02", "2027-02-03");

        List<String> withBlank = new ArrayList<>(valid.lines().toList());
        withBlank.add(10, "");
        assertInvalid(year, String.join("\n", withBlank));
    }

    @Test
    void fullYearValidationFailsClosedForEveryIdentityAndSchemaDefect() throws Exception {
        int year = 2026;
        Map<LocalDate, RowOverride> spring = Map.of(
                LocalDate.of(year, 2, 16), new RowOverride("2", "春節"));
        String valid = calendarCsv(year, spring, false);
        List<String> lines = new ArrayList<>(valid.lines().toList());

        assertInvalid(year, String.join("\n", lines.subList(0, lines.size() - 1))); // missing date

        List<String> duplicate = new ArrayList<>(lines);
        duplicate.set(2, duplicate.get(1));
        assertInvalid(year, String.join("\n", duplicate));

        List<String> wrongYear = new ArrayList<>(lines);
        wrongYear.set(1, wrongYear.get(1).replaceFirst("20260101", "20250101"));
        assertInvalid(year, String.join("\n", wrongYear));

        List<String> wrongWeekday = new ArrayList<>(lines);
        wrongWeekday.set(1, wrongWeekday.get(1).replaceFirst(",四,", ",五,"));
        assertInvalid(year, String.join("\n", wrongWeekday));

        List<String> unknownFlag = new ArrayList<>(lines);
        unknownFlag.set(1, unknownFlag.get(1).replaceFirst(",0,", ",1,"));
        assertInvalid(year, String.join("\n", unknownFlag));

        List<String> wrongHeader = new ArrayList<>(lines);
        wrongHeader.set(0, "西元日期,星期,放假,備註");
        assertInvalid(year, String.join("\n", wrongHeader));

        List<String> extraSchema = new ArrayList<>(lines);
        extraSchema.set(0, extraSchema.get(0) + ",額外");
        extraSchema.set(1, extraSchema.get(1) + ",x");
        assertInvalid(year, String.join("\n", extraSchema));

        List<String> malformedQuote = new ArrayList<>(lines);
        malformedQuote.set(1, malformedQuote.get(1) + ",\"unclosed");
        assertInvalid(year, String.join("\n", malformedQuote));

        assertInvalid(year, calendarCsv(year, Map.of(), false)); // no spring anchor

        int insufficientYear = 2024;
        assertInvalid(insufficientYear, calendarCsv(insufficientYear, Map.of(
                LocalDate.of(insufficientYear, 1, 2), new RowOverride("2", "春節")), false));
    }

    @Test
    void sharedQuotedParserPreservesCommaAndEscapedQuoteButRejectsMalformedQuotes() {
        assertThat(QuotedCsvRowParser.parse("a,\"b,c\",\"d\"\"e\""))
                .containsExactly("a", "b,c", "d\"e");
        assertThatThrownBy(() -> QuotedCsvRowParser.parse("a,\"unclosed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuotedCsvRowParser.parse("a,b\"ad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertInvalid(int year, String csv) throws Exception {
        assertThat(fetch(year, "UTF-8", csv.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    private static java.util.Optional<Map<String, String>> fetch(
            int year, String encoding, byte[] body) throws Exception {
        String metadata = metadata(List.of(resource(year, null, encoding, CSV, "2027-01-01 00:00:00")));
        return client(metadata, Map.of(CSV, body)).fetchHolidays(year);
    }

    private static DgpaCalendarClient client(String metadata, Map<URI, byte[]> downloads) {
        return new DgpaCalendarClient(MAPPER, uri -> {
            if (DgpaCalendarClient.METADATA_URI.equals(uri)) {
                return new DgpaCalendarClient.HttpPayload(200, metadata.getBytes(StandardCharsets.UTF_8));
            }
            byte[] body = downloads.get(uri);
            return new DgpaCalendarClient.HttpPayload(body == null ? 404 : 200, body);
        });
    }

    private static String metadata(List<Map<String, String>> resources) throws Exception {
        return MAPPER.writeValueAsString(Map.of("result", Map.of("distribution", resources)));
    }

    private static Map<String, String> resource(
            int year, String revision, String encoding, URI uri, String qualityTime) {
        String description = (year - 1911) + "年中華民國政府行政機關辦公日曆表"
                + (revision == null ? "" : "(" + revision + ")");
        Map<String, String> resource = new LinkedHashMap<>();
        resource.put("resourceDescription", description);
        resource.put("resourceFormat", "CSV");
        resource.put("resourceCharacterEncoding", encoding);
        resource.put("resourceDownloadUrl", uri.toString());
        resource.put("resourceQualityCheckTime", qualityTime);
        return resource;
    }

    private static URI uri(String filename) {
        return URI.create("https://www.dgpa.gov.tw/FileConversion?filename=" + filename + "&nfix=1&name=x");
    }

    private static String calendarCsv(
            int year, Map<LocalDate, RowOverride> overrides, boolean bom) {
        StringBuilder csv = new StringBuilder();
        if (bom) csv.append('\uFEFF');
        csv.append("西元日期,星期,是否放假,備註\n");
        LocalDate date = LocalDate.of(year, 1, 1);
        for (int i = 0; i < Year.of(year).length(); i++, date = date.plusDays(1)) {
            RowOverride override = overrides.get(date);
            String flag = override == null ? "0" : override.flag();
            String note = override == null ? "" : override.note();
            csv.append(date.format(DateTimeFormatter.BASIC_ISO_DATE)).append(',')
                    .append(weekday(date.getDayOfWeek())).append(',')
                    .append(flag).append(',')
                    .append(csvCell(note)).append('\n');
        }
        return csv.toString();
    }

    private static char weekday(DayOfWeek day) {
        return "一二三四五六日".charAt(day.getValue() - 1);
    }

    private static String csvCell(String value) {
        if (!value.contains(",") && !value.contains("\"") && !value.contains("\n")) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record RowOverride(String flag, String note) {}
}
