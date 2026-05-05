package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.KoreaGdpPerCapitaHistory;
import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.TwseIndexYearEndHistory;
import com.steven.assets.repository.KoreaGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TaiwanGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.TwseIndexYearEndHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 從外部來源回補總體經濟年度時間序列：
 * - 人均 GDP：IMF DataMapper API（NGDPDPC/TWN）
 * - 大盤年末收盤：TWSE FMTQIK 月報（每年 12 月最後一筆「發行量加權股價指數」）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroHistoryService {

    private static final String IMF_API_TPL =
            "https://www.imf.org/external/datamapper/api/v1/";
    private static final String IMF_GDP_INDICATOR = "NGDPDPC";        // 人均 GDP（USD）
    private static final String IMF_GROWTH_INDICATOR = "NGDP_RPCH";    // Real GDP growth %
    private static final String TWSE_FMTQIK_URL =
            "https://www.twse.com.tw/exchangeReport/FMTQIK?response=json&date=";

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    private final KoreaGdpPerCapitaHistoryRepository koreaGdpRepo;
    private final TwseIndexYearEndHistoryRepository twseRepo;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional
    public Map<String, Object> refreshGdpFromImf() throws Exception {
        Map<Integer, BigDecimal> gdp = fetchImf(IMF_GDP_INDICATOR, "TWN", 2);
        Map<Integer, BigDecimal> growth = fetchImf(IMF_GROWTH_INDICATOR, "TWN", 4);
        for (var e : gdp.entrySet()) {
            int year = e.getKey();
            TaiwanGdpPerCapitaHistory row = gdpRepo.findById(year)
                    .orElseGet(() -> {
                        TaiwanGdpPerCapitaHistory r = new TaiwanGdpPerCapitaHistory();
                        r.setYear(year);
                        return r;
                    });
            row.setGdpUsd(e.getValue());
            row.setRealGdpGrowthRate(growth.get(year));
            gdpRepo.save(row);
        }
        log.info("IMF refresh (TWN): {} 年 GDP, {} 年 growth", gdp.size(), growth.size());
        return Map.of("upserted", gdp.size(), "growthUpserted", growth.size(),
                "source", "IMF NGDPDPC+NGDP_RPCH/TWN");
    }

    @Transactional
    public Map<String, Object> refreshKoreaGdpFromImf() throws Exception {
        Map<Integer, BigDecimal> gdp = fetchImf(IMF_GDP_INDICATOR, "KOR", 2);
        Map<Integer, BigDecimal> growth = fetchImf(IMF_GROWTH_INDICATOR, "KOR", 4);
        for (var e : gdp.entrySet()) {
            int year = e.getKey();
            KoreaGdpPerCapitaHistory row = koreaGdpRepo.findById(year)
                    .orElseGet(() -> {
                        KoreaGdpPerCapitaHistory r = new KoreaGdpPerCapitaHistory();
                        r.setYear(year);
                        return r;
                    });
            row.setGdpUsd(e.getValue());
            row.setRealGdpGrowthRate(growth.get(year));
            koreaGdpRepo.save(row);
        }
        log.info("IMF refresh (KOR): {} 年 GDP, {} 年 growth", gdp.size(), growth.size());
        return Map.of("upserted", gdp.size(), "growthUpserted", growth.size(),
                "source", "IMF NGDPDPC+NGDP_RPCH/KOR");
    }

    private Map<Integer, BigDecimal> fetchImf(String indicator, String countryCode, int scale) throws Exception {
        // IMF 後面是 Akamai WAF；UA 設為 Mozilla 或 Java-http-client 會被 403。
        // 用 curl shell-out，沿用既有 HistoricalDataService 的模式（避免 Java TLS fingerprint 被擋）。
        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-sS", "--max-time", "20",
                "-H", "Accept: application/json",
                IMF_API_TPL + indicator + "/" + countryCode);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String body = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        if (exit != 0) throw new RuntimeException("curl exit=" + exit + ": " + body);
        JsonNode node = mapper.readTree(body)
                .path("values").path(indicator).path(countryCode);
        if (!node.isObject() || node.isEmpty()) {
            throw new RuntimeException("IMF 回應未含 " + indicator + "/" + countryCode);
        }
        Map<Integer, BigDecimal> out = new java.util.LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isNull()) continue;
            out.put(Integer.parseInt(e.getKey()),
                    BigDecimal.valueOf(e.getValue().asDouble()).setScale(scale, RoundingMode.HALF_UP));
        }
        return out;
    }

    /**
     * 逐年 (from..to) 抓 TWSE FMTQIK 12 月份月報，取該月最後一筆作為年末收盤。
     * 為避免被 TWSE 阻擋，每年呼叫之間 sleep 800ms。
     */
    @Transactional
    public Map<String, Object> refreshTwseYearEnd(int from, int to) throws Exception {
        int upserted = 0;
        int skipped = 0;
        for (int year = from; year <= to; year++) {
            BigDecimal close = fetchTwseDecemberClose(year);
            if (close == null) { skipped++; continue; }
            final int y = year;
            TwseIndexYearEndHistory row = twseRepo.findById(year)
                    .orElseGet(() -> {
                        TwseIndexYearEndHistory r = new TwseIndexYearEndHistory();
                        r.setYear(y);
                        return r;
                    });
            row.setClosePoint(close);
            twseRepo.save(row);
            upserted++;
            try { Thread.sleep(800); } catch (InterruptedException ignore) {}
        }
        log.info("TWSE year-end refresh {}~{}: upserted={}, skipped={}", from, to, upserted, skipped);
        return Map.of("upserted", upserted, "skipped", skipped, "from", from, "to", to);
    }

    private BigDecimal fetchTwseDecemberClose(int year) {
        try {
            String url = TWSE_FMTQIK_URL + year + "1201";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return null;
            JsonNode root = mapper.readTree(res.body());
            if (!"OK".equals(root.path("stat").asText())) return null;
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) return null;
            // 取最後一筆（該月最後一個交易日）
            JsonNode last = data.get(data.size() - 1);
            String idxStr = last.get(4).asText().replace(",", "");
            return new BigDecimal(idxStr).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("TWSE FMTQIK {} 抓取失敗：{}", year, e.getMessage());
            return null;
        }
    }

    /**
     * 抓取近 N 年（含當月）每日大盤收盤點位 upsert 至 `twse_index_daily_history`。
     * 逐月呼叫 TWSE FMTQIK，每月 ~20 筆交易日，sleep 800ms。
     * 10 年 ≈ 120 個月、~2 分鐘。
     */
    @Transactional
    public Map<String, Object> refreshTwseDaily(int years) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.YearMonth start = java.time.YearMonth.from(today).minusYears(years).plusMonths(1);
        java.time.YearMonth end = java.time.YearMonth.from(today);

        int upserted = 0;
        int skippedMonths = 0;
        int totalMonths = 0;
        for (java.time.YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            totalMonths++;
            List<TwseIndexDailyHistory> rows = fetchTwseMonthlyDaily(ym);
            if (rows.isEmpty()) {
                skippedMonths++;
            } else {
                twseDailyRepo.saveAll(rows);
                upserted += rows.size();
            }
            try { Thread.sleep(800); } catch (InterruptedException ignore) {}
        }
        log.info("TWSE daily refresh {}~{}: upserted={} rows, skippedMonths={}/{}",
                start, end, upserted, skippedMonths, totalMonths);
        return Map.of(
                "upserted", upserted,
                "skippedMonths", skippedMonths,
                "totalMonths", totalMonths,
                "from", start.toString(),
                "to", end.toString()
        );
    }

    /**
     * 抓單一月份 FMTQIK 月報。每筆 data：
     *   [民國日期(yyy/MM/dd), 成交股數, 成交金額, 成交筆數, 發行量加權股價指數收盤, 漲跌點數]
     */
    private List<TwseIndexDailyHistory> fetchTwseMonthlyDaily(java.time.YearMonth ym) {
        try {
            String date = String.format("%04d%02d01", ym.getYear(), ym.getMonthValue());
            HttpRequest req = HttpRequest.newBuilder(URI.create(TWSE_FMTQIK_URL + date))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return java.util.Collections.emptyList();
            JsonNode root = mapper.readTree(res.body());
            if (!"OK".equals(root.path("stat").asText())) return java.util.Collections.emptyList();
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) return java.util.Collections.emptyList();

            List<TwseIndexDailyHistory> out = new java.util.ArrayList<>();
            for (JsonNode row : data) {
                String mingoDate = row.get(0).asText();   // e.g. "114/05/02"
                String[] p = mingoDate.split("/");
                if (p.length != 3) continue;
                int year = Integer.parseInt(p[0]) + 1911;
                java.time.LocalDate d;
                try {
                    d = java.time.LocalDate.of(year, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                } catch (Exception e) { continue; }
                String idxStr = row.get(4).asText().replace(",", "");
                if (idxStr.isEmpty() || "-".equals(idxStr)) continue;
                BigDecimal close = new BigDecimal(idxStr).setScale(2, RoundingMode.HALF_UP);
                out.add(new TwseIndexDailyHistory(d, close));
            }
            return out;
        } catch (Exception e) {
            log.warn("TWSE FMTQIK {} 月報抓取失敗：{}", ym, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }
}
