package com.steven.assets.bff.gdptwse;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GdpTwseView（股市分析頁）專屬 BFF（Requirement 18）。
 * 指數日線/當日分時走 index-daily / index-intraday；本 get/refresh 服務「台日韓人均 GDP 比較」圖：
 *   - years
 *   - gdpPerCapitaUsd（台灣）/ japanGdpPerCapitaUsd（日本）/ koreaGdpPerCapitaUsd（韓國）
 *   - taiwanGdpGrowthRate / japanGdpGrowthRate / koreaGdpGrowthRate（年增率 %）
 */
@RestController
@RequestMapping("/api/bff/gdp-twse")
@RequiredArgsConstructor
public class GdpTwseBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestParam(defaultValue = "40") int years) {
        int currentYear = LocalDate.now().getYear();
        int since = currentYear - years + 1;

        Mono<List<Map<String, Object>>> gdp = fetchSeries("/api/taiwan-gdp", since);
        Mono<List<Map<String, Object>>> jpn = fetchSeries("/api/japan-gdp", since);
        Mono<List<Map<String, Object>>> kor = fetchSeries("/api/korea-gdp", since);

        return Mono.zip(gdp, jpn, kor).map(t -> {
            TreeMap<Integer, BigDecimal> twGdpAll = toMap(t.getT1(), "gdpUsd");
            TreeMap<Integer, BigDecimal> jpGdpAll = toMap(t.getT2(), "gdpUsd");
            TreeMap<Integer, BigDecimal> krGdpAll = toMap(t.getT3(), "gdpUsd");
            TreeMap<Integer, BigDecimal> twGrowthAll = toMap(t.getT1(), "realGdpGrowthRate");
            TreeMap<Integer, BigDecimal> jpGrowthAll = toMap(t.getT2(), "realGdpGrowthRate");
            TreeMap<Integer, BigDecimal> krGrowthAll = toMap(t.getT3(), "realGdpGrowthRate");

            // X 軸：since..currentYear 取 TW/JP/KR GDP 的聯集
            TreeMap<Integer, Boolean> yset = new TreeMap<>();
            twGdpAll.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });
            jpGdpAll.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });
            krGdpAll.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });

            List<Integer> yearsList = new ArrayList<>(yset.keySet());
            List<Object> twGdp = new ArrayList<>();
            List<Object> jpGdp = new ArrayList<>();
            List<Object> krGdp = new ArrayList<>();
            List<Object> twGrowth = new ArrayList<>();
            List<Object> jpGrowth = new ArrayList<>();
            List<Object> krGrowth = new ArrayList<>();
            for (Integer y : yearsList) {
                twGdp.add(twGdpAll.get(y));
                jpGdp.add(jpGdpAll.get(y));
                krGdp.add(krGdpAll.get(y));
                twGrowth.add(twGrowthAll.get(y));
                jpGrowth.add(jpGrowthAll.get(y));
                krGrowth.add(krGrowthAll.get(y));
            }

            Map<String, Object> body = new HashMap<>();
            body.put("years", yearsList);
            body.put("gdpPerCapitaUsd", twGdp);
            body.put("japanGdpPerCapitaUsd", jpGdp);
            body.put("koreaGdpPerCapitaUsd", krGdp);
            body.put("taiwanGdpGrowthRate", twGrowth);
            body.put("japanGdpGrowthRate", jpGrowth);
            body.put("koreaGdpGrowthRate", krGrowth);
            return ResponseEntity.ok(body);
        });
    }

    private Mono<List<Map<String, Object>>> fetchSeries(String path, int since) {
        return businessServicesClient.get()
                .uri(uri -> uri.path(path).queryParam("since", since).build())
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());
    }

    private TreeMap<Integer, BigDecimal> toMap(List<Map<String, Object>> rows, String valueKey) {
        TreeMap<Integer, BigDecimal> m = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            Object v = r.get(valueKey);
            if (v == null) continue;
            int y = ((Number) r.get("year")).intValue();
            m.put(y, new BigDecimal(v.toString()));
        }
        return m;
    }

    /**
     * 並行觸發 TWN/JPN/KOR 人均 GDP（IMF / DGBAS）回補，供「台日韓人均 GDP 比較」圖使用。
     */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh(
            @RequestParam(defaultValue = "40") int years) {
        ParameterizedTypeReference<Map<String, Object>> mapRef = new ParameterizedTypeReference<>() {};

        Mono<Map<String, Object>> gdp = businessServicesClient.post()
                .uri("/api/taiwan-gdp/refresh-from-imf")
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

        Mono<Map<String, Object>> jpn = businessServicesClient.post()
                .uri("/api/japan-gdp/refresh-from-imf")
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

        Mono<Map<String, Object>> kor = businessServicesClient.post()
                .uri("/api/korea-gdp/refresh-from-imf")
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

        return Mono.zip(gdp, jpn, kor).map(tuple -> {
            Map<String, Object> body = new HashMap<>();
            body.put("gdp", tuple.getT1());
            body.put("japan", tuple.getT2());
            body.put("korea", tuple.getT3());
            return ResponseEntity.ok(body);
        });
    }

    /**
     * 指數日線（近 N 年）+ MA20 / MA60 / MA240。一次回傳完整資料；前端切換區間僅用 dataZoom 不重打 API。
     * market=TWSE 走台股大盤（/api/twse-daily-index），其餘（DJI/SPX/IXIC/SOX/FTSE/DAX/KOSPI/N225）走海外指數（/api/us-daily-index）。
     * 兩市場回傳格式與 MA 計算完全相同（同義欄位同一來源），確保版面一致。
     */
    @GetMapping("/index-daily")
    public Mono<ResponseEntity<Map<String, Object>>> getIndexDaily(
            @RequestParam(defaultValue = "TWSE") String market,
            @RequestParam(defaultValue = "10") int years) {
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusYears(years);
        boolean tw = "TWSE".equalsIgnoreCase(market);

        return businessServicesClient.get()
                .uri(uri -> {
                    if (tw) {
                        return uri.path("/api/twse-daily-index")
                                .queryParam("from", fromDate.toString())
                                .queryParam("to", today.toString())
                                .build();
                    }
                    return uri.path("/api/us-daily-index")
                            .queryParam("code", market)
                            .queryParam("from", fromDate.toString())
                            .queryParam("to", today.toString())
                            .build();
                })
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(rows -> {
                    int n = rows.size();
                    List<String> dates = new ArrayList<>(n);
                    List<BigDecimal> closes = new ArrayList<>(n);
                    for (Map<String, Object> r : rows) {
                        Object d = r.get("tradingDate");
                        Object c = r.get("closePoint");
                        if (d == null || c == null) continue;
                        dates.add(d.toString());
                        closes.add(new BigDecimal(c.toString()));
                    }
                    Map<String, Object> body = new HashMap<>();
                    body.put("dates", dates);
                    body.put("closes", closes);
                    body.put("ma20", movingAverage(closes, 20));
                    body.put("ma60", movingAverage(closes, 60));
                    body.put("ma240", movingAverage(closes, 240));
                    return ResponseEntity.ok(body);
                });
    }

    /**
     * 觸發「當前選取」指數的日線回補。market=TWSE → 台股逐月 TWSE 月報（耗時 1~2 分鐘）；
     * 其餘 → 海外指數 Yahoo v8 chart（range=10y，一次呼叫即整段）。
     */
    @PostMapping("/refresh-index-daily")
    public Mono<ResponseEntity<Map<String, Object>>> refreshIndexDaily(
            @RequestParam(defaultValue = "TWSE") String market,
            @RequestParam(defaultValue = "10") int years) {
        ParameterizedTypeReference<Map<String, Object>> mapRef = new ParameterizedTypeReference<>() {};
        boolean tw = "TWSE".equalsIgnoreCase(market);
        return businessServicesClient.post()
                .uri(uri -> tw
                        ? uri.path("/api/twse-daily-index/refresh").queryParam("years", years).build()
                        : uri.path("/api/us-daily-index/refresh").queryParam("code", market).build())
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(180))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())))
                .map(ResponseEntity::ok);
    }

    /**
     * 指數「當日」分時走勢。market=TWSE→^TWII、其餘→對應海外指數；回最新交易日整天 5 分 K 收盤。
     * 回 tradingDate（YYYY-MM-DD）+ times（HH:mm）+ closes；另回昨收/漲跌/漲跌%供標題列顯示。
     *
     * 昨收（previousClose）＝該指數日線表中「tradingDate 之前最後一個交易日」收盤——與觀察清單 0000
     * 報價 WatchStockService 讀同一張日線表（同義欄位同一事實來源、值一致）。以 Mono.zip 並行抓
     * intraday 與近 40 日日線 tail（同 index-daily 的 business API），避免序列等待；計算放 BFF，前端只 render。
     * 漲跌＝分時最新點位（closes 末筆非 null＝盤中即時 / 盤後收盤）− 昨收。
     */
    @GetMapping("/index-intraday")
    public Mono<ResponseEntity<Map<String, Object>>> getIndexIntraday(
            @RequestParam(defaultValue = "TWSE") String market) {
        boolean tw = "TWSE".equalsIgnoreCase(market);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(40);   // 40 日涵蓋最長連假，確保 tail 含前一交易日

        Mono<List<Map<String, Object>>> intradayMono = businessServicesClient.get()
                .uri(uri -> uri.path("/api/index-intraday").queryParam("market", market).build())
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<List<Map<String, Object>>> dailyMono = businessServicesClient.get()
                .uri(uri -> tw
                        ? uri.path("/api/twse-daily-index")
                                .queryParam("from", from.toString())
                                .queryParam("to", today.toString())
                                .build()
                        : uri.path("/api/us-daily-index")
                                .queryParam("code", market)
                                .queryParam("from", from.toString())
                                .queryParam("to", today.toString())
                                .build())
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        return Mono.zip(intradayMono, dailyMono).map(tuple -> {
            List<Map<String, Object>> rows = tuple.getT1();
            List<Map<String, Object>> daily = tuple.getT2();

            int n = rows.size();
            List<String> times = new ArrayList<>(n);
            List<BigDecimal> closes = new ArrayList<>(n);
            String tradingDate = null;
            BigDecimal lastClose = null;
            for (Map<String, Object> r : rows) {
                Object t = r.get("time");
                if (t == null) continue;
                Object c = r.get("close");          // 盤中尚未到的時段為 null，保留時間、close 留 null（x 軸延伸到收盤時間）
                String ts = t.toString();            // "2026-06-10T13:30:00"
                if (tradingDate == null && ts.length() >= 10) tradingDate = ts.substring(0, 10);
                times.add(ts.length() >= 16 ? ts.substring(11, 16) : ts);  // HH:mm
                BigDecimal cv = (c == null) ? null : new BigDecimal(c.toString());
                closes.add(cv);
                if (cv != null) lastClose = cv;     // 末筆非 null＝當前/最新點位（盤中即時、盤後收盤）
            }

            BigDecimal previousClose = previousCloseBefore(daily, tradingDate);
            BigDecimal change = null;
            BigDecimal changePercent = null;
            if (lastClose != null && previousClose != null && previousClose.signum() != 0) {
                change = lastClose.subtract(previousClose).setScale(2, java.math.RoundingMode.HALF_UP);
                changePercent = lastClose.subtract(previousClose)
                        .divide(previousClose, 6, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }

            Map<String, Object> body = new HashMap<>();
            body.put("tradingDate", tradingDate);
            body.put("times", times);
            body.put("closes", closes);
            body.put("previousClose", previousClose);
            body.put("lastClose", lastClose);
            body.put("change", change);
            body.put("changePercent", changePercent);
            return ResponseEntity.ok(body);
        });
    }

    /**
     * 日線（tradingDate asc）中「tradingDate 嚴格早於 beforeDate」的最後一筆收盤；找不到回 null。
     * 以 ISO 日期字串字典序＝時間序比較，無需轉 LocalDate。
     */
    private BigDecimal previousCloseBefore(List<Map<String, Object>> daily, String beforeDate) {
        if (beforeDate == null) return null;
        BigDecimal prev = null;
        for (Map<String, Object> r : daily) {
            Object d = r.get("tradingDate");
            Object c = r.get("closePoint");
            if (d == null || c == null) continue;
            if (d.toString().compareTo(beforeDate) < 0) {
                prev = new BigDecimal(c.toString());   // asc：持續覆寫到最後一筆 < beforeDate
            } else {
                break;                                  // 已達 >= beforeDate，後續更大，停
            }
        }
        return prev;
    }

    // ===== Excel 匯出與排程自動匯出（Requirement 45 / Task 216）=====
    // 排程設定為 per-user：沿用 businessServicesClient（WebClientConfig.tenantHeaderFilter 自動帶 X-User-*
    // → 後端 ownerFilter 縮到本人）。指數日線本身是全域公開行情，匯出內容不因使用者而異。

    /**
     * GET /api/bff/gdp-twse/export?market=&start=&end= — passthrough 下載 .xlsx。
     * 連同 business 回的 Content-Disposition 一併轉出，前端才能取到預設檔名。
     */
    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam(defaultValue = "TWSE") String market,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return businessServicesClient.get()
                .uri(uri -> {
                    var u = uri.path("/api/index-daily/export").queryParam("market", market);
                    if (start != null && !start.isBlank()) u.queryParam("start", start);
                    if (end != null && !end.isBlank()) u.queryParam("end", end);
                    return u.build();
                })
                .accept(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .toEntity(byte[].class)
                .map(e -> {
                    ResponseEntity.BodyBuilder b = ResponseEntity.ok();
                    e.getHeaders().forEach((k, v) -> v.forEach(val -> b.header(k, val)));
                    return b.body(e.getBody());
                });
    }

    @GetMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> getExportSchedule() {
        return businessServicesClient.get()
                .uri("/api/index-export/schedule")
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    @org.springframework.web.bind.annotation.PutMapping("/export/schedule")
    public Mono<ResponseEntity<Map<String, Object>>> updateExportSchedule(
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        return businessServicesClient.put()
                .uri("/api/index-export/schedule")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/export/run-now")
    public Mono<ResponseEntity<Map<String, Object>>> runExportNow() {
        return businessServicesClient.post()
                .uri("/api/index-export/run-now")
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /** 目錄列舉沿用 Requirement 34 既有的 business 端點（語意相同＝列出基底下子目錄），不新增第六份實作。 */
    @GetMapping("/export/browse")
    public Mono<ResponseEntity<Map<String, Object>>> browseExportDir(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return businessServicesClient.get()
                .uri("/api/export-schedule/browse?subpath={subpath}", subpath)
                .retrieve()
                .bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /**
     * 簡單移動平均：window 不足時填 null。回傳 List<Object>（可含 null）。
     */
    private List<Object> movingAverage(List<BigDecimal> values, int window) {
        int n = values.size();
        List<Object> out = new ArrayList<>(n);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            sum = sum.add(values.get(i));
            if (i >= window) sum = sum.subtract(values.get(i - window));
            if (i >= window - 1) {
                out.add(sum.divide(BigDecimal.valueOf(window), 2, java.math.RoundingMode.HALF_UP));
            } else {
                out.add(null);
            }
        }
        return out;
    }
}
