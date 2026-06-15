package com.steven.assets.service;

import com.steven.assets.model.KoreaGdpPerCapitaHistory;
import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.KoreaGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TaiwanGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
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

    /**
     * 海外指數合法代碼（日線回補 refresh 守門 + 自動回補排程共用單一清單）。
     * 美股四大：道瓊 / 標普500 / 那斯達克綜合 / 費城半導體；海外主要：英國富時100 / 德國DAX / 韓國KOSPI / 日經225。
     */
    public static final List<String> OVERSEAS_INDEX_CODES =
            List.of("DJI", "SPX", "IXIC", "SOX", "FTSE", "DAX", "KOSPI", "N225");

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    private final KoreaGdpPerCapitaHistoryRepository koreaGdpRepo;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;
    private final UsIndexDailyHistoryRepository usDailyRepo;
    private final WebClient priceServiceClient;

    public MacroHistoryService(
            TaiwanGdpPerCapitaHistoryRepository gdpRepo,
            KoreaGdpPerCapitaHistoryRepository koreaGdpRepo,
            TwseIndexDailyHistoryRepository twseDailyRepo,
            UsIndexDailyHistoryRepository usDailyRepo,
            @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.gdpRepo = gdpRepo;
        this.koreaGdpRepo = koreaGdpRepo;
        this.twseDailyRepo = twseDailyRepo;
        this.usDailyRepo = usDailyRepo;
        this.priceServiceClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    /**
     * 台灣人均 GDP + 實質 GDP 成長率回補：主計總處（DGBAS）為主、IMF 為備援。
     * DGBAS NA8101A1A 提供官方逐年實際值（1951 起、無未來預測），優先採用；
     * DGBAS 缺的年份（未來預測年 2026+、或抓取失敗時）以 IMF NGDPDPC/NGDP_RPCH 補。
     * 韓國無 DGBAS 對應來源，仍走純 IMF（{@link #refreshKoreaGdpFromImf()}）。
     */
    @Transactional
    public Map<String, Object> refreshGdpFromImf() throws Exception {
        Map<Integer, BigDecimal> imfGdp = fetchImfProxy(IMF_GDP_INDICATOR, "TWN", 2);
        Map<Integer, BigDecimal> imfGrowth = fetchImfProxy(IMF_GROWTH_INDICATOR, "TWN", 4);
        DgbasData dgbas = fetchDgbasProxy();   // 台灣官方，優先

        java.util.TreeSet<Integer> years = new java.util.TreeSet<>();
        years.addAll(imfGdp.keySet());
        years.addAll(imfGrowth.keySet());
        years.addAll(dgbas.gdpUsd().keySet());
        years.addAll(dgbas.growth().keySet());

        int dgbasGdpHits = 0, dgbasGrowthHits = 0;
        for (int year : years) {
            BigDecimal gdpUsd = dgbas.gdpUsd().get(year);
            if (gdpUsd != null) dgbasGdpHits++; else gdpUsd = imfGdp.get(year);
            BigDecimal growth = dgbas.growth().get(year);
            if (growth != null) dgbasGrowthHits++; else growth = imfGrowth.get(year);
            if (gdpUsd == null && growth == null) continue;

            final int y = year;
            TaiwanGdpPerCapitaHistory row = gdpRepo.findById(y)
                    .orElseGet(() -> {
                        TaiwanGdpPerCapitaHistory r = new TaiwanGdpPerCapitaHistory();
                        r.setYear(y);
                        return r;
                    });
            row.setGdpUsd(gdpUsd);
            row.setRealGdpGrowthRate(growth);
            gdpRepo.save(row);
        }
        log.info("TWN GDP refresh: {} 年（DGBAS 人均 {} 年 / 成長率 {} 年，其餘 IMF fallback）",
                years.size(), dgbasGdpHits, dgbasGrowthHits);
        return Map.of("upserted", years.size(),
                "dgbasGdpYears", dgbasGdpHits, "dgbasGrowthYears", dgbasGrowthHits,
                "source", "DGBAS NA8101A1A (primary) + IMF NGDPDPC/NGDP_RPCH (fallback)");
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

    /** DGBAS 國民所得：實質 GDP 成長率 + 人均 GDP(USD) 逐年值。 */
    private record DgbasData(Map<Integer, BigDecimal> growth, Map<Integer, BigDecimal> gdpUsd) {}

    /**
     * 呼叫 ext-materials-service 取主計總處 NA8101A1A。
     * 回 {"growth":{year:val}, "gdpUsd":{year:val}}（JSON key 為 String，轉 Integer）。
     * 抓取失敗 → 空 DgbasData，呼叫端全部 fallback IMF。
     */
    private DgbasData fetchDgbasProxy() {
        try {
            Map<String, Map<String, BigDecimal>> raw = priceServiceClient.get()
                    .uri("/internal/macro/dgbas")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Map<String, BigDecimal>>>() {})
                    .block();
            if (raw == null) return new DgbasData(Map.of(), Map.of());
            return new DgbasData(toIntKey(raw.get("growth")), toIntKey(raw.get("gdpUsd")));
        } catch (Exception e) {
            log.warn("呼叫 /internal/macro/dgbas 失敗: {}", e.getMessage());
            return new DgbasData(Map.of(), Map.of());
        }
    }

    private Map<Integer, BigDecimal> toIntKey(Map<String, BigDecimal> m) {
        if (m == null) return Map.of();
        Map<Integer, BigDecimal> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> {
            try { out.put(Integer.parseInt(k), v); } catch (NumberFormatException ignore) {}
        });
        return out;
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

    private record DailyOhlcDto(String tradingDate, BigDecimal open, BigDecimal high,
                                 BigDecimal low, BigDecimal close) {}

    private List<TwseIndexDailyHistory> fetchTwseMonthlyDailyProxy(YearMonth ym) {
        try {
            DailyOhlcDto[] arr = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/twse-monthly")
                            .queryParam("year", ym.getYear())
                            .queryParam("month", ym.getMonthValue()).build())
                    .retrieve()
                    .bodyToMono(DailyOhlcDto[].class)
                    .block();
            if (arr == null) return List.of();
            List<TwseIndexDailyHistory> out = new ArrayList<>();
            for (DailyOhlcDto d : arr) {
                if (d.tradingDate() == null || d.close() == null) continue;
                out.add(new TwseIndexDailyHistory(
                        LocalDate.parse(d.tradingDate()),
                        d.open(), d.high(), d.low(), d.close()));
            }
            return out;
        } catch (Exception e) {
            log.warn("呼叫 /internal/macro/twse-monthly {} 失敗: {}", ym, e.getMessage());
            return List.of();
        }
    }

    /**
     * 海外單一指數（DJI/SPX/IXIC/SOX/FTSE/DAX/KOSPI/N225）近 10 年日線回補。
     * 經 /internal/macro/us-index proxy 取 Yahoo v8 chart（range=10y，一次呼叫即整段），upsert 至 us_index_daily_history。
     */
    @Transactional
    public Map<String, Object> refreshUsIndexDaily(String code) {
        List<UsIndexDailyHistory> rows = fetchUsIndexDailyProxy(code);
        if (!rows.isEmpty()) usDailyRepo.saveAll(rows);
        String from = rows.isEmpty() ? "" : rows.get(0).getTradingDate().toString();
        String to = rows.isEmpty() ? "" : rows.get(rows.size() - 1).getTradingDate().toString();
        log.info("US index {} daily refresh: upserted={} rows ({}~{})", code, rows.size(), from, to);
        return Map.of("code", code, "upserted", rows.size(), "from", from, "to", to);
    }

    private List<UsIndexDailyHistory> fetchUsIndexDailyProxy(String code) {
        try {
            DailyOhlcDto[] arr = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/us-index")
                            .queryParam("code", code).build())
                    .retrieve()
                    .bodyToMono(DailyOhlcDto[].class)
                    .block();
            if (arr == null) return List.of();
            List<UsIndexDailyHistory> out = new ArrayList<>();
            for (DailyOhlcDto d : arr) {
                if (d.tradingDate() == null || d.close() == null) continue;
                out.add(new UsIndexDailyHistory(code, LocalDate.parse(d.tradingDate()),
                        d.open(), d.high(), d.low(), d.close()));
            }
            return out;
        } catch (Exception e) {
            log.warn("呼叫 /internal/macro/us-index {} 失敗: {}", code, e.getMessage());
            return List.of();
        }
    }

    /** 指數「當日」分時一點：time 為當地時區 ISO LocalDateTime、close 為 5 分 K 收盤。 */
    public record IntradayPoint(String time, BigDecimal close) {}

    /**
     * 指數「當日」分時走勢 proxy（transient，不寫 DB）。
     * market ∈ {TWSE,DJI,SPX,IXIC,SOX,FTSE,DAX,KOSPI,N225}；回最新交易日整天的 5 分 K 收盤序列。
     */
    public List<IntradayPoint> fetchIndexIntraday(String market) {
        try {
            IntradayPoint[] arr = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/index-intraday")
                            .queryParam("market", market).build())
                    .retrieve()
                    .bodyToMono(IntradayPoint[].class)
                    .block();
            return arr == null ? List.of() : java.util.Arrays.asList(arr);
        } catch (Exception e) {
            log.warn("呼叫 /internal/macro/index-intraday {} 失敗: {}", market, e.getMessage());
            return List.of();
        }
    }
}
