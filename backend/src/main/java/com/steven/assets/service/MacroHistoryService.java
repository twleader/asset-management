package com.steven.assets.service;

import com.steven.assets.model.JapanGdpPerCapitaHistory;
import com.steven.assets.model.KoreaGdpPerCapitaHistory;
import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.JapanGdpPerCapitaHistoryRepository;
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
 *  - 人均 GDP / 成長率：台灣以主計總處 DGBAS（NA8101A1A）為主、IMF（NGDPDPC、NGDP_RPCH）備援；韓國（KOR）、日本（JPN）走純 IMF
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

    /**
     * 「股市大盤查詢」頁可選指數＝海外指數 ∪ 台股大盤（Requirement 43 / Task 209 匯出白名單的單一來源）。
     *
     * <p>刻意<b>不含 {@code SP500TR}</b>：該代碼雖存在於 {@code us_index_daily_history}，
     * 但屬績效比較頁（Requirement 33）的含息報酬指數，不在本頁下拉中。
     * 與 {@code MacroHistoryController.US_INDEX_REFRESH_CODES}（回補守門，含 SP500TR）語意不同，不可互用。
     */
    public static final java.util.Set<String> DAILY_INDEX_CODES =
            java.util.stream.Stream.concat(java.util.stream.Stream.of("TWSE"), OVERSEAS_INDEX_CODES.stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * 「含息報酬指數」型海外指數代碼（績效比較頁 Requirement 33）。
     * SP500TR＝S&P 500 Total Return（含股息再投入），走 Yahoo ^SP500TR，與純價格 SPX 對照。
     * refresh 守門與每日自動回補排程共用。
     */
    public static final List<String> TOTAL_RETURN_US_INDEX_CODES = List.of("SP500TR");

    /** TWSE 報酬指數回補重入防護：避免連點 refresh-tr 同時 spawn 多條背景執行緒。 */
    private final java.util.concurrent.atomic.AtomicBoolean twseTrBackfillRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    private final JapanGdpPerCapitaHistoryRepository japanGdpRepo;
    private final KoreaGdpPerCapitaHistoryRepository koreaGdpRepo;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;
    private final UsIndexDailyHistoryRepository usDailyRepo;
    private final WebClient priceServiceClient;

    public MacroHistoryService(
            TaiwanGdpPerCapitaHistoryRepository gdpRepo,
            JapanGdpPerCapitaHistoryRepository japanGdpRepo,
            KoreaGdpPerCapitaHistoryRepository koreaGdpRepo,
            TwseIndexDailyHistoryRepository twseDailyRepo,
            UsIndexDailyHistoryRepository usDailyRepo,
            @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.gdpRepo = gdpRepo;
        this.japanGdpRepo = japanGdpRepo;
        this.koreaGdpRepo = koreaGdpRepo;
        this.twseDailyRepo = twseDailyRepo;
        this.usDailyRepo = usDailyRepo;
        this.priceServiceClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    // ========== 股市分析頁查詢（read）— 由 MacroHistoryController 委派（Controller 不直接讀 repository） ==========

    /** 台灣人均 GDP 逐年序列（升冪）；since 非 null 時只回該年（含）以後。 */
    @Transactional(readOnly = true)
    public List<TaiwanGdpPerCapitaHistory> getTaiwanGdp(Integer since) {
        return since == null
                ? gdpRepo.findAllByOrderByYearAsc()
                : gdpRepo.findByYearGreaterThanEqualOrderByYearAsc(since);
    }

    /** 日本人均 GDP 逐年序列（升冪）；since 非 null 時只回該年（含）以後。 */
    @Transactional(readOnly = true)
    public List<JapanGdpPerCapitaHistory> getJapanGdp(Integer since) {
        return since == null
                ? japanGdpRepo.findAllByOrderByYearAsc()
                : japanGdpRepo.findByYearGreaterThanEqualOrderByYearAsc(since);
    }

    /** 韓國人均 GDP 逐年序列（升冪）；since 非 null 時只回該年（含）以後。 */
    @Transactional(readOnly = true)
    public List<KoreaGdpPerCapitaHistory> getKoreaGdp(Integer since) {
        return since == null
                ? koreaGdpRepo.findAllByOrderByYearAsc()
                : koreaGdpRepo.findByYearGreaterThanEqualOrderByYearAsc(since);
    }

    /** 台股大盤日線（升冪）；from/to 皆有回區間、僅 from 回該日起、皆無回全部。 */
    @Transactional(readOnly = true)
    public List<TwseIndexDailyHistory> getTwseDaily(LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            return twseDailyRepo.findByTradingDateBetweenOrderByTradingDateAsc(from, to);
        }
        if (from != null) {
            return twseDailyRepo.findByTradingDateGreaterThanEqualOrderByTradingDateAsc(from);
        }
        return twseDailyRepo.findAllByOrderByTradingDateAsc();
    }

    /** 海外指數日線（升冪）；from/to 皆有回區間、僅 from 回該日起、皆無回該 code 全部。 */
    @Transactional(readOnly = true)
    public List<UsIndexDailyHistory> getUsDaily(String code, LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            return usDailyRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(code, from, to);
        }
        if (from != null) {
            return usDailyRepo.findByIndexCodeAndTradingDateGreaterThanEqualOrderByTradingDateAsc(code, from);
        }
        return usDailyRepo.findByIndexCodeOrderByTradingDateAsc(code);
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

    /** 日本人均 GDP + 實質成長率回補（純 IMF，DGBAS 無日本資料，比照韓國）。 */
    @Transactional
    public Map<String, Object> refreshJapanGdpFromImf() throws Exception {
        Map<Integer, BigDecimal> gdp = fetchImfProxy(IMF_GDP_INDICATOR, "JPN", 2);
        Map<Integer, BigDecimal> growth = fetchImfProxy(IMF_GROWTH_INDICATOR, "JPN", 4);
        for (var e : gdp.entrySet()) {
            int year = e.getKey();
            JapanGdpPerCapitaHistory row = japanGdpRepo.findById(year)
                    .orElseGet(() -> {
                        JapanGdpPerCapitaHistory r = new JapanGdpPerCapitaHistory();
                        r.setYear(year);
                        return r;
                    });
            row.setGdpUsd(e.getValue());
            row.setRealGdpGrowthRate(growth.get(year));
            japanGdpRepo.save(row);
        }
        log.info("IMF refresh (JPN): {} 年 GDP, {} 年 growth", gdp.size(), growth.size());
        return Map.of("upserted", gdp.size(), "growthUpserted", growth.size(),
                "source", "IMF NGDPDPC+NGDP_RPCH/JPN");
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
                // 保留既有 close_point_tr（報酬指數）：fetchTwseMonthlyDailyProxy 只帶價格 OHLC，
                // 直接 saveAll（JPA merge）會把已回補的 close_point_tr 洗成 null（含息 TWSE 線靜默退化）。
                for (TwseIndexDailyHistory row : rows) {
                    twseDailyRepo.findById(row.getTradingDate())
                            .ifPresent(ex -> row.setClosePointTr(ex.getClosePointTr()));
                }
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
                        d.open(), d.high(), d.low(), d.close(),
                        null));   // closePointTr 由 refresh-tr / 每日排程另行回補
            }
            return out;
        } catch (Exception e) {
            log.warn("呼叫 /internal/macro/twse-monthly {} 失敗: {}", ym, e.getMessage());
            return List.of();
        }
    }

    /** TWSE 發行量加權股價「報酬指數」（含息）某交易日收盤 proxy 回傳。 */
    private record TwseReturnIndexDto(String tradingDate, BigDecimal close) {}

    /**
     * 抓「單日」TWSE 報酬指數收盤（含息）。
     * 經 /internal/macro/twse-return-index proxy 打 ext-materials（非交易日/查無回 204）；
     * 回 BigDecimal close，204 或例外回 null。
     */
    private BigDecimal fetchTwseReturnIndexProxy(LocalDate date) {
        try {
            TwseReturnIndexDto dto = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/twse-return-index")
                            .queryParam("date", date.toString()).build())
                    .retrieve()
                    .bodyToMono(TwseReturnIndexDto.class)
                    .block();
            return dto == null ? null : dto.close();
        } catch (Exception e) {
            log.warn("呼叫 /internal/macro/twse-return-index {} 失敗: {}", date, e.getMessage());
            return null;
        }
    }

    /**
     * TWSE 報酬指數（含息）近 N 年回補：**背景執行**、立即回 {"started":true,"pending":&lt;n&gt;}。
     * 取 twse_index_daily_history 近 N 年 close_point_tr 為 null 的列，逐日抓報酬指數收盤補上；
     * 逐筆各自 save（resumable），每筆 350ms 禮貌間隔，單筆失敗 try/catch 續跑，結束 log 統計。
     */
    public Map<String, Object> refreshTwseReturnIndex(int years) {
        if (!twseTrBackfillRunning.compareAndSet(false, true)) {
            return Map.of("started", false, "reason", "in-progress");   // 重入防護
        }
        List<TwseIndexDailyHistory> pending = pendingTwseReturnIndexRows(LocalDate.now().minusYears(years));
        int pendingCount = pending.size();
        new Thread(() -> {
            try {
                fillTwseReturnIndex(pending, "backfill-" + years + "y");
            } finally {
                twseTrBackfillRunning.set(false);
            }
        }, "twse-return-index-backfill").start();
        return Map.of("started", true, "pending", pendingCount);
    }

    /**
     * 每日增量 / self-heal：補近 {@code lookbackDays} 天內仍為 null 的 close_point_tr
     * （涵蓋最近交易日、排程時點今日列尚未寫入、以及回補時的單日 miss 缺口）。同步執行、回補上筆數。
     */
    public int fillRecentTwseReturnIndexGaps(int lookbackDays) {
        List<TwseIndexDailyHistory> pending = pendingTwseReturnIndexRows(LocalDate.now().minusDays(lookbackDays));
        fillTwseReturnIndex(pending, "daily-gap");
        return pending.size();
    }

    /**
     * TWSE 報酬指數合理性檢查：報酬指數/價格指數比值歷史約 1.5–2.1（隨配息複利緩升），
     * 落在 [1.3, 3.5] 外視為 TWSE 單次 transient 異常值（如實測 2021-12-23 曾回 104241／比值 5.8），拒存以免整條含息線爆尖刺。
     * 無同日價格可比時放行（無從判定）。
     */
    private static boolean isPlausibleTr(BigDecimal tr, BigDecimal price) {
        if (tr == null || tr.signum() <= 0) return false;
        if (price == null || price.signum() <= 0) return true;
        double ratio = tr.doubleValue() / price.doubleValue();
        return ratio >= 1.3 && ratio <= 3.5;
    }

    /** 取指定日起、close_point_tr 仍為 null 的交易日列（升冪）。 */
    private List<TwseIndexDailyHistory> pendingTwseReturnIndexRows(LocalDate since) {
        return twseDailyRepo.findByTradingDateGreaterThanEqualOrderByTradingDateAsc(since)
                .stream()
                .filter(r -> r.getClosePointTr() == null)
                .toList();
    }

    /** 逐日抓報酬指數收盤補上 close_point_tr；逐筆各自 save（resumable），每筆 350ms 禮貌間隔，單筆失敗續跑。 */
    private void fillTwseReturnIndex(List<TwseIndexDailyHistory> pending, String tag) {
        int ok = 0, miss = 0, fail = 0;
        for (TwseIndexDailyHistory row : pending) {
            try {
                BigDecimal tr = fetchTwseReturnIndexProxy(row.getTradingDate());
                if (tr != null && isPlausibleTr(tr, row.getClosePoint())) {
                    row.setClosePointTr(tr);
                    twseDailyRepo.save(row);   // 逐筆各自存，resumable
                    ok++;
                } else {
                    if (tr != null) {   // 抓到但值不合理（TWSE 單次 transient 異常）→ 拒存，留 null 待下次重試
                        log.warn("TWSE 報酬指數 {} 值不合理（TR={}, price={}）→ 略過不存", row.getTradingDate(), tr, row.getClosePoint());
                    }
                    miss++;
                }
                Thread.sleep(350);   // 禮貌間隔
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                fail++;
                log.warn("回補 TWSE 報酬指數 {} 失敗: {}", row.getTradingDate(), e.getMessage());
            }
        }
        log.info("TWSE 報酬指數回補 [{}]（pending {}）：補上 {} 筆、查無 {} 筆、失敗 {} 筆",
                tag, pending.size(), ok, miss, fail);
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
