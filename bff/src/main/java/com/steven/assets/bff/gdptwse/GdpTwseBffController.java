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
 * 指數日線/當日分時走 index-daily / index-intraday；本 get/refresh 服務「台韓人均 GDP 比較」圖：
 *   - years
 *   - gdpPerCapitaUsd（台灣）/ koreaGdpPerCapitaUsd（韓國）
 *   - taiwanGdpGrowthRate / koreaGdpGrowthRate（年增率 %）
 */
@RestController
@RequestMapping("/api/bff/gdp-twse")
@RequiredArgsConstructor
public class GdpTwseBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestParam(defaultValue = "30") int years) {
        int currentYear = LocalDate.now().getYear();
        int since = currentYear - years + 1;

        Mono<List<Map<String, Object>>> gdp = fetchSeries("/api/taiwan-gdp", since);
        Mono<List<Map<String, Object>>> kor = fetchSeries("/api/korea-gdp", since);

        return Mono.zip(gdp, kor).map(t -> {
            TreeMap<Integer, BigDecimal> twGdpAll = toMap(t.getT1(), "gdpUsd");
            TreeMap<Integer, BigDecimal> krGdpAll = toMap(t.getT2(), "gdpUsd");
            TreeMap<Integer, BigDecimal> twGrowthAll = toMap(t.getT1(), "realGdpGrowthRate");
            TreeMap<Integer, BigDecimal> krGrowthAll = toMap(t.getT2(), "realGdpGrowthRate");

            // X 軸：since..currentYear 取 TW/KR GDP 的聯集
            TreeMap<Integer, Boolean> yset = new TreeMap<>();
            twGdpAll.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });
            krGdpAll.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });

            List<Integer> yearsList = new ArrayList<>(yset.keySet());
            List<Object> twGdp = new ArrayList<>();
            List<Object> krGdp = new ArrayList<>();
            List<Object> twGrowth = new ArrayList<>();
            List<Object> krGrowth = new ArrayList<>();
            for (Integer y : yearsList) {
                twGdp.add(twGdpAll.get(y));
                krGdp.add(krGdpAll.get(y));
                twGrowth.add(twGrowthAll.get(y));
                krGrowth.add(krGrowthAll.get(y));
            }

            Map<String, Object> body = new HashMap<>();
            body.put("years", yearsList);
            body.put("gdpPerCapitaUsd", twGdp);
            body.put("koreaGdpPerCapitaUsd", krGdp);
            body.put("taiwanGdpGrowthRate", twGrowth);
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
     * 並行觸發 TWN/KOR 人均 GDP（IMF / DGBAS）回補，供「台韓人均 GDP 比較」圖使用。
     */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh(
            @RequestParam(defaultValue = "30") int years) {
        ParameterizedTypeReference<Map<String, Object>> mapRef = new ParameterizedTypeReference<>() {};

        Mono<Map<String, Object>> gdp = businessServicesClient.post()
                .uri("/api/taiwan-gdp/refresh-from-imf")
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

        Mono<Map<String, Object>> kor = businessServicesClient.post()
                .uri("/api/korea-gdp/refresh-from-imf")
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

        return Mono.zip(gdp, kor).map(tuple -> {
            Map<String, Object> body = new HashMap<>();
            body.put("gdp", tuple.getT1());
            body.put("korea", tuple.getT2());
            return ResponseEntity.ok(body);
        });
    }

    /**
     * 指數日線（近 N 年）+ MA20 / MA60 / MA240。一次回傳完整資料；前端切換區間僅用 dataZoom 不重打 API。
     * market=TWSE 走台股大盤（/api/twse-daily-index），其餘（DJI/SPX/IXIC/SOX）走美股指數（/api/us-daily-index）。
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
     * 其餘 → 美股 Yahoo v8 chart（range=10y，一次呼叫即整段）。
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
     * 指數「當日」分時走勢。market=TWSE→^TWII、其餘→對應美股指數；回最新交易日整天 5 分 K 收盤。
     * 回 tradingDate（YYYY-MM-DD）+ times（HH:mm）+ closes；前端「當日」模式用。
     */
    @GetMapping("/index-intraday")
    public Mono<ResponseEntity<Map<String, Object>>> getIndexIntraday(
            @RequestParam(defaultValue = "TWSE") String market) {
        return businessServicesClient.get()
                .uri(uri -> uri.path("/api/index-intraday").queryParam("market", market).build())
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(rows -> {
                    int n = rows.size();
                    List<String> times = new ArrayList<>(n);
                    List<BigDecimal> closes = new ArrayList<>(n);
                    String tradingDate = null;
                    for (Map<String, Object> r : rows) {
                        Object t = r.get("time");
                        if (t == null) continue;
                        Object c = r.get("close");          // 盤中尚未到的時段為 null，保留時間、close 留 null（x 軸延伸到收盤時間）
                        String ts = t.toString();            // "2026-06-10T13:30:00"
                        if (tradingDate == null && ts.length() >= 10) tradingDate = ts.substring(0, 10);
                        times.add(ts.length() >= 16 ? ts.substring(11, 16) : ts);  // HH:mm
                        closes.add(c == null ? null : new BigDecimal(c.toString()));
                    }
                    Map<String, Object> body = new HashMap<>();
                    body.put("tradingDate", tradingDate);
                    body.put("times", times);
                    body.put("closes", closes);
                    return ResponseEntity.ok(body);
                });
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
