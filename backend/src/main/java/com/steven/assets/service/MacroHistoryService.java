package com.steven.assets.service;

import com.steven.assets.model.KoreaGdpPerCapitaHistory;
import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.TwseIndexYearEndHistory;
import com.steven.assets.repository.KoreaGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TaiwanGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.TwseIndexYearEndHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 從外部來源回補總體經濟年度時間序列：
 *  - 人均 GDP / 成長率：IMF DataMapper API（NGDPDPC、NGDP_RPCH，TWN/KOR）
 *  - 大盤年末 / 月線：TWSE FMTQIK 月報
 *
 * 對外抓取已搬到 ext-materials-service `MacroDataFetchClient`，本 service 透過
 * `/internal/macro/*` proxy 取得資料後寫入 JPA repositories。
 */
@Slf4j
@Service
public class MacroHistoryService {

    private static final String IMF_GDP_INDICATOR = "NGDPDPC";
    private static final String IMF_GROWTH_INDICATOR = "NGDP_RPCH";

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    private final KoreaGdpPerCapitaHistoryRepository koreaGdpRepo;
    private final TwseIndexYearEndHistoryRepository twseRepo;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;
    private final WebClient priceServiceClient;

    public MacroHistoryService(
            TaiwanGdpPerCapitaHistoryRepository gdpRepo,
            KoreaGdpPerCapitaHistoryRepository koreaGdpRepo,
            TwseIndexYearEndHistoryRepository twseRepo,
            TwseIndexDailyHistoryRepository twseDailyRepo,
            @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.gdpRepo = gdpRepo;
        this.koreaGdpRepo = koreaGdpRepo;
        this.twseRepo = twseRepo;
        this.twseDailyRepo = twseDailyRepo;
        this.priceServiceClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    @Transactional
    public Map<String, Object> refreshGdpFromImf() throws Exception {
        Map<Integer, BigDecimal> gdp = fetchImfProxy(IMF_GDP_INDICATOR, "TWN", 2);
        Map<Integer, BigDecimal> growth = fetchImfProxy(IMF_GROWTH_INDICATOR, "TWN", 4);
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
        Map<Integer, BigDecimal> gdp = fetchImfProxy(IMF_GDP_INDICATOR, "KOR", 2);
        Map<Integer, BigDecimal> growth = fetchImfProxy(IMF_GROWTH_INDICATOR, "KOR", 4);
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

    private Map<Integer, BigDecimal> fetchImfProxy(String indicator, String country, int scale) {
        try {
            // IMF response keys are integer years (YYYY) but Map<Integer, ...> via JSON arrives as Map<String, ...>;
            // 用 ParameterizedTypeReference 解析後手動轉。
            Map<String, BigDecimal> raw = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/imf")
                            .queryParam("indicator", indicator)
                            .queryParam("country", country)
                            .queryParam("scale", scale).build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, BigDecimal>>() {})
                    .block();
            if (raw == null) return Map.of();
            Map<Integer, BigDecimal> out = new java.util.LinkedHashMap<>();
            raw.forEach((k, v) -> {
                try { out.put(Integer.parseInt(k), v); } catch (NumberFormatException ignore) {}
            });
            return out;
        } catch (Exception e) {
            log.warn("呼叫 /internal/macro/imf 失敗 {} {}: {}", indicator, country, e.getMessage());
            return Map.of();
        }
    }

    @Transactional
    public Map<String, Object> refreshTwseYearEnd(int from, int to) {
        int upserted = 0;
        int skipped = 0;
        for (int year = from; year <= to; year++) {
            BigDecimal close = fetchTwseDecemberCloseProxy(year);
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

    private BigDecimal fetchTwseDecemberCloseProxy(int year) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/twse-year-end")
                            .queryParam("year", year).build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (resp == null) return null;
            Object cp = resp.get("closePoint");
            if (cp == null) return null;
            if (cp instanceof Number n) return BigDecimal.valueOf(n.doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP);
            return new BigDecimal(cp.toString());
        } catch (Exception e) {
            log.warn("呼叫 /internal/macro/twse-year-end {} 失敗: {}", year, e.getMessage());
            return null;
        }
    }

    @Transactional
    public Map<String, Object> refreshTwseDaily(int years) {
        LocalDate today = LocalDate.now();
        YearMonth start = YearMonth.from(today).minusYears(years).plusMonths(1);
        YearMonth end = YearMonth.from(today);

        int upserted = 0;
        int skippedMonths = 0;
        int totalMonths = 0;
        for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            totalMonths++;
            List<TwseIndexDailyHistory> rows = fetchTwseMonthlyDailyProxy(ym);
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
                "to", end.toString());
    }

    private record DailyCloseDto(String tradingDate, BigDecimal close) {}

    private List<TwseIndexDailyHistory> fetchTwseMonthlyDailyProxy(YearMonth ym) {
        try {
            DailyCloseDto[] arr = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/twse-monthly")
                            .queryParam("year", ym.getYear())
                            .queryParam("month", ym.getMonthValue()).build())
                    .retrieve()
                    .bodyToMono(DailyCloseDto[].class)
                    .block();
            if (arr == null) return List.of();
            List<TwseIndexDailyHistory> out = new ArrayList<>();
            for (DailyCloseDto d : arr) {
                if (d.tradingDate() == null || d.close() == null) continue;
                out.add(new TwseIndexDailyHistory(LocalDate.parse(d.tradingDate()), d.close()));
            }
            return out;
        } catch (Exception e) {
            log.warn("呼叫 /internal/macro/twse-monthly {} 失敗: {}", ym, e.getMessage());
            return List.of();
        }
    }
}
