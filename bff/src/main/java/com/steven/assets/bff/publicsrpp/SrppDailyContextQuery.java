package com.steven.assets.bff.publicsrpp;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code GET /api/public/srpp/daily-context} 的 query 嚴格解析（純函式、零 I/O；Requirement 163）。
 *
 * <p>任一不合法（未知 key、重複 key、空值、前後空白、格式錯、view 組合錯、GET body）一律拋
 * {@link SrppProblemException}({@link SrppProblemCatalog#INVALID_REQUEST})，呼叫端因此在 owner lookup 與
 * business 呼叫前就結束。產生的 business URI 只帶驗證後參數，<b>不帶 email</b>。
 */
public record SrppDailyContextQuery(
        String tradingDate,
        String slot,
        String policyBundleSha256,
        String email,
        View view,
        String packageId,
        String sourceId) {

    public enum View { SUMMARY, EVIDENCE }

    static final String BUSINESS_PATH = "/internal/public-srpp/daily-context";

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "tradingDate", "slot", "policyBundleSha256", "email", "view", "packageId", "sourceId");
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    private static final Set<String> SLOTS = Set.of("09:05", "11:40");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final int EMAIL_MAX_LENGTH = 254;
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern SOURCE_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");

    /** 解析並驗證；失敗一律 {@code INVALID_REQUEST}。 */
    public static SrppDailyContextQuery parse(MultiValueMap<String, String> params, HttpHeaders headers) {
        if (headers != null && (headers.getContentLength() > 0 || headers.containsKey(HttpHeaders.TRANSFER_ENCODING))) {
            throw invalid();
        }
        if (params == null) {
            throw invalid();
        }
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            if (!ALLOWED_KEYS.contains(entry.getKey())) throw invalid();
            List<String> values = entry.getValue();
            if (values == null || values.size() != 1) throw invalid();
            String value = values.get(0);
            if (value == null || value.isEmpty() || !value.equals(value.strip())) throw invalid();
        }
        String tradingDate = required(params, "tradingDate");
        String slot = required(params, "slot");
        String hash = required(params, "policyBundleSha256");
        String email = params.getFirst("email");
        String viewValue = params.getFirst("view");
        String packageId = params.getFirst("packageId");
        String sourceId = params.getFirst("sourceId");

        if (!isDate(tradingDate)) throw invalid();
        if (!SLOTS.contains(slot)) throw invalid();
        if (!SHA256.matcher(hash).matches()) throw invalid();
        if (email != null && (email.length() > EMAIL_MAX_LENGTH || !EMAIL.matcher(email).matches())) throw invalid();
        View view;
        if (viewValue == null || "summary".equals(viewValue)) {
            view = View.SUMMARY;
        } else if ("evidence".equals(viewValue)) {
            view = View.EVIDENCE;
        } else {
            throw invalid();
        }
        if (packageId != null && !UUID.matcher(packageId).matches()) throw invalid();
        if (sourceId != null && !SOURCE_ID.matcher(sourceId).matches()) throw invalid();
        if (view == View.EVIDENCE && (packageId == null || sourceId == null)) throw invalid();
        if (view == View.SUMMARY && sourceId != null) throw invalid();
        return new SrppDailyContextQuery(tradingDate, slot, hash, email, view, packageId, sourceId);
    }

    /** summary 且未指定 packageId：只接受 CURRENT 的最新發布。 */
    public boolean latestMode() {
        return view == View.SUMMARY && packageId == null;
    }

    /** business 內部端點的 path＋query；刻意不含 email（owner 只經顯式 X-User-* header 傳遞）。 */
    public String businessUri() {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(BUSINESS_PATH)
                .queryParam("tradingDate", tradingDate)
                .queryParam("slot", slot)
                .queryParam("policyBundleSha256", policyBundleSha256)
                .queryParam("view", view == View.EVIDENCE ? "evidence" : "summary");
        if (packageId != null) builder.queryParam("packageId", packageId);
        if (sourceId != null) builder.queryParam("sourceId", sourceId);
        return builder.encode().build().toUriString();
    }

    private static String required(MultiValueMap<String, String> params, String key) {
        String value = params.getFirst(key);
        if (value == null) throw invalid();
        return value;
    }

    private static boolean isDate(String value) {
        if (!DATE.matcher(value).matches()) return false;
        try {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static SrppProblemException invalid() {
        return new SrppProblemException(SrppProblemCatalog.INVALID_REQUEST);
    }
}
