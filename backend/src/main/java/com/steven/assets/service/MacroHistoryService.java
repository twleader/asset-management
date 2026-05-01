package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexYearEndHistory;
import com.steven.assets.repository.TaiwanGdpPerCapitaHistoryRepository;
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

    private static final String IMF_GDP_URL =
            "https://www.imf.org/external/datamapper/api/v1/NGDPDPC/TWN";
    private static final String TWSE_FMTQIK_URL =
            "https://www.twse.com.tw/exchangeReport/FMTQIK?response=json&date=";

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    private final TwseIndexYearEndHistoryRepository twseRepo;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional
    public Map<String, Object> refreshGdpFromImf() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(IMF_GDP_URL))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("IMF API HTTP " + res.statusCode());
        }
        JsonNode twn = mapper.readTree(res.body())
                .path("values").path("NGDPDPC").path("TWN");
        if (!twn.isObject() || twn.isEmpty()) {
            throw new RuntimeException("IMF 回應未含 TWN 資料");
        }

        int upserted = 0;
        Iterator<Map.Entry<String, JsonNode>> it = twn.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            int year = Integer.parseInt(e.getKey());
            if (e.getValue().isNull()) continue;
            BigDecimal v = BigDecimal.valueOf(e.getValue().asDouble())
                    .setScale(2, RoundingMode.HALF_UP);
            TaiwanGdpPerCapitaHistory row = gdpRepo.findById(year)
                    .orElseGet(() -> {
                        TaiwanGdpPerCapitaHistory r = new TaiwanGdpPerCapitaHistory();
                        r.setYear(year);
                        return r;
                    });
            row.setGdpUsd(v);
            gdpRepo.save(row);
            upserted++;
        }
        log.info("IMF GDP refresh: upserted {} 年", upserted);
        return Map.of("upserted", upserted, "source", "IMF NGDPDPC/TWN");
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
}
