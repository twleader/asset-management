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
 * GdpTwseView 專屬 BFF（Requirement 18）。
 * 回傳近 N 年（預設 30）對齊好的：
 *   - years
 *   - gdpPerCapitaUsd（台灣）/ koreaGdpPerCapitaUsd（韓國）
 *   - twseYearEndClose
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
        Mono<List<Map<String, Object>>> twse = fetchSeries("/api/twse-year-end-index", since);
        // 當年（尚未到 12/31）以「最後一個交易日大盤收盤」代替年末收盤
        Mono<List<Map<String, Object>>> twseLatest = businessServicesClient.get()
                .uri("/api/twse-daily-index/latest")
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        return Mono.zip(gdp, kor, twse, twseLatest).map(t -> {
            TreeMap<Integer, BigDecimal> twGdpAll = toMap(t.getT1(), "gdpUsd");
            TreeMap<Integer, BigDecimal> krGdpAll = toMap(t.getT2(), "gdpUsd");
            TreeMap<Integer, BigDecimal> twGrowthAll = toMap(t.getT1(), "realGdpGrowthRate");
            TreeMap<Integer, BigDecimal> krGrowthAll = toMap(t.getT2(), "realGdpGrowthRate");
            TreeMap<Integer, BigDecimal> twseClose = toMap(t.getT3(), "closePoint");

            // 當年若無 12/31 收盤，從最近一筆 daily index（必須落在當年）回填
            String currentYearLastTradingDate = null;
            if (!twseClose.containsKey(currentYear) && !t.getT4().isEmpty()) {
                Map<String, Object> latest = t.getT4().get(0);
                Object d = latest.get("tradingDate");
                Object c = latest.get("closePoint");
                if (d != null && c != null) {
                    String ds = d.toString();
                    if (ds.length() >= 4 && Integer.parseInt(ds.substring(0, 4)) == currentYear) {
                        twseClose.put(currentYear, new BigDecimal(c.toString()));
                        currentYearLastTradingDate = ds;
                    }
                }
            }

            // X 軸：since..currentYear 取所有 series 的聯集
            TreeMap<Integer, Boolean> yset = new TreeMap<>();
            twGdpAll.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });
            krGdpAll.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });
            twseClose.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });

            List<Integer> yearsList = new ArrayList<>(yset.keySet());
            List<Object> twGdp = new ArrayList<>();
            List<Object> krGdp = new ArrayList<>();
            List<Object> twseList = new ArrayList<>();
            List<Object> twGrowth = new ArrayList<>();
            List<Object> krGrowth = new ArrayList<>();
            for (Integer y : yearsList) {
                twGdp.add(twGdpAll.get(y));
                krGdp.add(krGdpAll.get(y));
                twseList.add(twseClose.get(y));
                twGrowth.add(twGrowthAll.get(y));
                krGrowth.add(krGrowthAll.get(y));
            }

            Map<String, Object> body = new HashMap<>();
            body.put("years", yearsList);
            body.put("gdpPerCapitaUsd", twGdp);
            body.put("koreaGdpPerCapitaUsd", krGdp);
            body.put("twseYearEndClose", twseList);
            body.put("taiwanGdpGrowthRate", twGrowth);
            body.put("koreaGdpGrowthRate", krGrowth);
            body.put("currentYearLastTradingDate", currentYearLastTradingDate);
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
     * 並行觸發 TWN/KOR GDP（IMF）+ 大盤年末收盤 + 大盤日線（TWSE FMTQIK）四項回補。
     */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh(
            @RequestParam(defaultValue = "30") int years) {
        int currentYear = LocalDate.now().getYear();
        int from = currentYear - years + 1;

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

        // 30 年 × ~0.8s sleep + http roundtrip → 預計 30~60s
        Mono<Map<String, Object>> twse = businessServicesClient.post()
                .uri(uri -> uri.path("/api/twse-year-end-index/refresh")
                        .queryParam("from", from).queryParam("to", currentYear).build())
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(120))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

        // 10 年 × 12 個月 × 0.8s sleep ≈ 100s
        Mono<Map<String, Object>> twseDaily = businessServicesClient.post()
                .uri(uri -> uri.path("/api/twse-daily-index/refresh")
                        .queryParam("years", 10).build())
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(180))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

        return Mono.zip(gdp, kor, twse, twseDaily).map(tuple -> {
            Map<String, Object> body = new HashMap<>();
            body.put("gdp", tuple.getT1());
            body.put("korea", tuple.getT2());
            body.put("twse", tuple.getT3());
            body.put("twseDaily", tuple.getT4());
            return ResponseEntity.ok(body);
        });
    }

    /**
     * 大盤日線（近 N 年）+ MA20 / MA60 / MA240。
     * 一次回傳完整資料；前端切換區間僅用 dataZoom 不重打 API。
     */
    @GetMapping("/twse-daily")
    public Mono<ResponseEntity<Map<String, Object>>> getTwseDaily(
            @RequestParam(defaultValue = "10") int years) {
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusYears(years);

        return businessServicesClient.get()
                .uri(uri -> uri.path("/api/twse-daily-index")
                        .queryParam("from", fromDate.toString())
                        .queryParam("to", today.toString())
                        .build())
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
     * 觸發 10 年大盤日線單獨回補（不含 GDP / 年末），耗時 1~2 分鐘。
     */
    @PostMapping("/refresh-twse-daily")
    public Mono<ResponseEntity<Map<String, Object>>> refreshTwseDaily(
            @RequestParam(defaultValue = "10") int years) {
        ParameterizedTypeReference<Map<String, Object>> mapRef = new ParameterizedTypeReference<>() {};
        return businessServicesClient.post()
                .uri(uri -> uri.path("/api/twse-daily-index/refresh")
                        .queryParam("years", years).build())
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(180))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())))
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
