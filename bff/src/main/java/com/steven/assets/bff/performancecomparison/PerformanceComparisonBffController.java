package com.steven.assets.bff.performancecomparison;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * PerformanceComparisonView（績效比較頁，Requirement 33）專屬 BFF（一頁一支）。
 *
 * <ul>
 *   <li>{@code GET /my-stocks} — 代理 business 的「我的股票」清單（WebClient 自動帶 X-User-Id → owner 生效）。</li>
 *   <li>{@code GET /compare} — 並行抓各標的日線（股票 closePrice／指數 closePoint），做 union 交易日軸 +
 *       forward-fill + 「同起點=0%」報酬率正規化，回 render-ready 資料。計算集中於 BFF，前端只 render
 *       （比照 {@code GdpTwseBffController} 在 BFF 算 MA 的分工）。</li>
 * </ul>
 *
 * 刻意不呼叫 {@code /api/stock-alerts/lookup-name}（該端點對未知 code 會打外部 API 並 upsert 寫 stock 主檔，
 * 屬寫副作用，不放進 GET 聚合）；series 的顯示 label 由前端用 my-stocks 清單（股票）＋固定常數（基準）自行組合。
 */
@RestController
@RequestMapping("/api/bff/performance-comparison")
@RequiredArgsConstructor
public class PerformanceComparisonBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    /** 可比較的大盤基準白名單（us-daily-index GET 不驗 code，故由 BFF 守門）。 */
    private static final Set<String> ALLOWED_BENCHMARKS = Set.of("TWSE", "DJI", "SPX", "IXIC", "SOX");

    private static final int MAX_STOCKS = 3;
    private static final int MAX_BENCHMARKS = 5;

    /** 待抓取的一個標的：type=stock|index。stock 帶 code+market；index 只有 code（market=null）。 */
    private record Target(String key, String type, String code, String market, int idx) {}

    /** 抓回並整理成 date→close 的一個標的（priceOnly=僅純價格、非含息，供前端標示）。 */
    private record SeriesRaw(Target target, TreeMap<String, BigDecimal> closes, boolean priceOnly) {}

    /** 除息事件（股利再投入用）：exDate 為 ISO 字串、cash 現金股利、stock 配股（面額 10）。 */
    private record ExDiv(String exDate, BigDecimal cash, BigDecimal stock) {}

    /** 我的股票下拉清單（owner-scoped）：代理 business。 */
    @GetMapping("/my-stocks")
    public Mono<ResponseEntity<List<Map<String, Object>>>> myStocks() {
        return businessServicesClient.get()
                .uri("/api/performance-comparison/my-stocks")
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(ResponseEntity::ok);
    }

    /**
     * 績效比較：回 {@code { dates:[union 交易日], series:[{key,type,code,market,returns[],totalReturn,asOfDate}] }}。
     *
     * @param stocks     逗號分隔的「code:market」（最多 3 檔）
     * @param benchmarks 逗號分隔的基準代碼（白名單 {TWSE,DJI,SPX,IXIC,SOX}，最多 5）
     * @param range      1m / 3m / 6m / 1y / 2y / 5y / 10y（預設 1y）
     * @param dividend   true=含息報酬（股利再投入／報酬指數）、false=純價格報酬（預設 true）
     */
    @GetMapping("/compare")
    public Mono<ResponseEntity<Map<String, Object>>> compare(
            @RequestParam(required = false) String stocks,
            @RequestParam(required = false) String benchmarks,
            @RequestParam(defaultValue = "1y") String range,
            @RequestParam(defaultValue = "true") boolean dividend) {

        List<Target> targets = buildTargets(stocks, benchmarks);
        if (targets.isEmpty()) {
            return Mono.just(ResponseEntity.ok(emptyBody()));
        }

        LocalDate to = LocalDate.now();
        LocalDate from = rangeStart(range, to);

        return Flux.fromIterable(targets)
                .flatMap(t -> fetchCloses(t, from, to, dividend))
                .collectList()
                .map(list -> {
                    // flatMap 回來的順序不定 → 依輸入索引還原，維持圖例/摘要順序穩定
                    list.sort(Comparator.comparingInt(sr -> sr.target().idx()));
                    return ResponseEntity.ok(normalize(list));
                });
    }

    // ─────────────────────────── 參數解析 ───────────────────────────

    private List<Target> buildTargets(String stocks, String benchmarks) {
        List<Target> targets = new ArrayList<>();
        int idx = 0;

        for (String token : dedupLimit(splitCsv(stocks), MAX_STOCKS)) {
            int p = token.indexOf(':');   // code pattern 不含 ':'、market 中文也不含 ':' → 切第一個即可
            if (p <= 0 || p == token.length() - 1) continue;
            String code = token.substring(0, p).trim();
            String market = token.substring(p + 1).trim();
            if (code.isEmpty() || market.isEmpty()) continue;
            targets.add(new Target(code + ":" + market, "stock", code, market, idx++));
        }

        for (String token : dedupLimit(splitCsv(benchmarks), MAX_BENCHMARKS)) {
            String code = token.trim().toUpperCase();
            if (!ALLOWED_BENCHMARKS.contains(code)) continue;   // 白名單守門
            targets.add(new Target(code, "index", code, null, idx++));
        }
        return targets;
    }

    /** 逗號切分 → trim → 去空 token（處理 trailing comma / 前後空白）。 */
    private List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .toList();
    }

    private List<String> dedupLimit(List<String> in, int max) {
        return in.stream().distinct().limit(max).toList();
    }

    private LocalDate rangeStart(String range, LocalDate to) {
        return switch (range == null ? "1y" : range.toLowerCase()) {
            case "1m" -> to.minusMonths(1);
            case "3m" -> to.minusMonths(3);
            case "6m" -> to.minusMonths(6);
            case "2y" -> to.minusYears(2);
            case "5y" -> to.minusYears(5);
            case "10y" -> to.minusYears(10);
            default -> to.minusYears(1);   // 1y 及未知值
        };
    }

    // ─────────────────────────── 抓取 ───────────────────────────

    /**
     * 抓單一標的區間日線 → 帶回 {@link SeriesRaw}（含 priceOnly 標記）。
     * 逐標的 timeout + 降級，單線失敗只讓該線消失，絕不讓整條 500。
     */
    private Mono<SeriesRaw> fetchCloses(Target t, LocalDate from, LocalDate to, boolean dividend) {
        if ("stock".equals(t.type())) {
            return fetchStockSeries(t, from, to, dividend);
        }
        return fetchIndexSeries(t, from, to, dividend);
    }

    // ── 指數 ──

    /**
     * 指數取數：
     * <ul>
     *   <li>dividend=false：一律讀 closePoint（價格指數）、priceOnly=false。</li>
     *   <li>dividend=true：TWSE 每列優先讀報酬指數 closePointTr（null 退回 closePoint）；
     *       SPX 改抓 SP500TR 的 closePoint（對外 code 仍回 "SPX"）；以上 priceOnly=false。
     *       DJI/IXIC/SOX 無含息來源 → 讀 closePoint、priceOnly=true。</li>
     * </ul>
     */
    private Mono<SeriesRaw> fetchIndexSeries(Target t, LocalDate from, LocalDate to, boolean dividend) {
        String code = t.code();
        if (!dividend) {
            return fetchIndexRows(code, from, to)
                    .map(rows -> new SeriesRaw(t, toPriceTree(rows), false));
        }
        if ("TWSE".equals(code)) {
            // 含息：只用報酬指數 closePointTr，禁止與價格指數 closePoint 混量級（兩者量級差 ~1.6 倍，
            // 混填會在回補前緣/缺口造成台階跳空）。TR 覆蓋率不足（尚未回補完成）→ 退回價格指數並標 priceOnly=true。
            return fetchIndexRows("TWSE", from, to).map(rows -> {
                TreeMap<String, BigDecimal> tr = toTrTree(rows);       // 僅取非 null closePointTr（null 列略過，由 floorEntry 前值延伸）
                TreeMap<String, BigDecimal> price = toPriceTree(rows);
                boolean trReady = !tr.isEmpty() && tr.size() * 10 >= price.size() * 9;   // ≥90% 交易日有報酬指數
                return trReady ? new SeriesRaw(t, tr, false)
                               : new SeriesRaw(t, price, true);
            });
        }
        if ("SPX".equals(code)) {
            // 含息：改抓 SP500TR（closePoint 即報酬指數量級），對外 code 仍回 "SPX"；
            // 尚未回補（空）→ 退回 SPX 價格指數並標 priceOnly=true，避免整條線消失。
            return fetchIndexRows("SP500TR", from, to).flatMap(trRows -> {
                TreeMap<String, BigDecimal> tr = toPriceTree(trRows);
                if (!tr.isEmpty()) return Mono.just(new SeriesRaw(t, tr, false));
                return fetchIndexRows("SPX", from, to)
                        .map(pxRows -> new SeriesRaw(t, toPriceTree(pxRows), true));
            });
        }
        // DJI / IXIC / SOX：無報酬指數來源 → 純價格
        return fetchIndexRows(code, from, to)
                .map(rows -> new SeriesRaw(t, toPriceTree(rows), true));
    }

    /** 依 fetchCode 打對應指數端點（TWSE → twse-daily-index；其餘 → us-daily-index?code=）。 */
    private Mono<List<Map<String, Object>>> fetchIndexRows(String fetchCode, LocalDate from, LocalDate to) {
        return businessServicesClient.get()
                .uri(uri -> {
                    if ("TWSE".equals(fetchCode)) {
                        return uri.path("/api/twse-daily-index")
                                .queryParam("from", from.toString())
                                .queryParam("to", to.toString())
                                .build();
                    }
                    return uri.path("/api/us-daily-index")
                            .queryParam("code", fetchCode)
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString())
                            .build();
                })
                .retrieve().bodyToMono(LIST_MAP)
                .timeout(Duration.ofSeconds(8))
                .onErrorReturn(Collections.emptyList());
    }

    /** 指數 rows → date→價格指數 closePoint TreeMap。 */
    private TreeMap<String, BigDecimal> toPriceTree(List<Map<String, Object>> rows) {
        return toIndexTree(rows, "closePoint");
    }

    /** 指數 rows → date→報酬指數 closePointTr TreeMap；closePointTr 為 null 的列直接略過（不退回價格指數，避免混量級）。 */
    private TreeMap<String, BigDecimal> toTrTree(List<Map<String, Object>> rows) {
        return toIndexTree(rows, "closePointTr");
    }

    private TreeMap<String, BigDecimal> toIndexTree(List<Map<String, Object>> rows, String key) {
        TreeMap<String, BigDecimal> tree = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            Object d = r.get("tradingDate");
            Object c = r.get(key);
            if (d == null || c == null) continue;
            try {
                tree.put(d.toString(), new BigDecimal(c.toString()));
            } catch (NumberFormatException ignore) {
                // 非數值 → 略過該列
            }
        }
        return tree;
    }

    // ── 個股 ──

    /**
     * 個股取數：先抓日線收盤（date→closePrice）。
     * <ul>
     *   <li>dividend=false：用原始 closes、priceOnly=false。</li>
     *   <li>dividend=true 且 market 為台股/美股/英股：並行抓除息事件；
     *       無股利資料（英股目前 stock_dividend_history 皆無）→ 原始 closes、priceOnly=true；
     *       有資料 → 股利再投入調整、priceOnly=false。</li>
     * </ul>
     */
    private Mono<SeriesRaw> fetchStockSeries(Target t, LocalDate from, LocalDate to, boolean dividend) {
        Mono<TreeMap<String, BigDecimal>> closesMono = fetchStockCloses(t, from, to);
        if (!dividend) {
            return closesMono.map(closes -> new SeriesRaw(t, closes, false));
        }
        // 含息：台股 / 美股 / 英股 皆嘗試抓除息事件做股利再投入；
        // 英股 stock_dividend_history 無資料（抓取端未實作）→ 空 → 退回原始價並標 priceOnly=true。
        // （不臆測英股為累積型：配息型 LSE ETF 如 VUSA.L 原始價不含息，硬當含息會低估且誤標。）
        int years = to.getYear() - from.getYear() + 2;   // 涵蓋區間（多留 2 年緩衝）
        Mono<List<Map<String, Object>>> divsMono = fetchDividends(t.code(), t.market(), years);
        return Mono.zip(closesMono, divsMono)
                .map(tuple -> {
                    TreeMap<String, BigDecimal> closes = tuple.getT1();
                    List<Map<String, Object>> divs = tuple.getT2();
                    if (divs.isEmpty()) {
                        // 無股利資料 → 原始 closes、標記純價格
                        return new SeriesRaw(t, closes, true);
                    }
                    return new SeriesRaw(t, reinvestDividends(closes, divs, to), false);
                });
    }

    /** 抓個股區間日線 → date→closePrice TreeMap；timeout + 降級。 */
    private Mono<TreeMap<String, BigDecimal>> fetchStockCloses(Target t, LocalDate from, LocalDate to) {
        return businessServicesClient.get()
                .uri(uri -> uri.path("/api/market-data/history/stock")
                        .queryParam("code", t.code())
                        .queryParam("market", t.market())
                        .queryParam("start", from.toString())
                        .queryParam("end", to.toString())
                        .build())
                .retrieve().bodyToMono(LIST_MAP)
                .timeout(Duration.ofSeconds(8))
                .onErrorReturn(Collections.emptyList())
                .map(rows -> toTree(rows, "closePrice"));
    }

    /** 純讀 stock_dividend_history（絕不觸發 cold-cache 寫副作用）；失敗 → 空陣列（視同無股利）。 */
    private Mono<List<Map<String, Object>>> fetchDividends(String code, String market, int years) {
        return businessServicesClient.get()
                .uri(uri -> uri.path("/api/market-data/dividends-readonly")
                        .queryParam("code", code)
                        .queryParam("market", market)
                        .queryParam("years", years)
                        .build())
                .retrieve().bodyToMono(LIST_MAP)
                .timeout(Duration.ofSeconds(8))
                .onErrorReturn(Collections.emptyList());
    }

    /** rows → date(ISO 字串)→close TreeMap（忽略缺欄或非數值列）。 */
    private TreeMap<String, BigDecimal> toTree(List<Map<String, Object>> rows, String closeKey) {
        TreeMap<String, BigDecimal> tree = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            Object d = r.get("tradingDate");
            Object c = r.get(closeKey);
            if (d == null || c == null) continue;
            try {
                tree.put(d.toString(), new BigDecimal(c.toString()));
            } catch (NumberFormatException ignore) {
                // 非數值收盤（理論上不會發生）→ 略過該列
            }
        }
        return tree;
    }

    // ── 股利再投入（等效 total-return index） ──

    /**
     * 把原始 closes 轉為含息調整 closes：每逢除息日，用「1 + 配股/10 + 現金/收盤」放大持股數，
     * 各日調整值 = 持股數 × 當日收盤。丟回既有 normalize()/pct() 即得含息報酬%。
     *
     * @param closes 已排序的 date→close
     * @param divs   dividends-readonly 裸陣列（每筆含 exDividendDate/cashDividend/stockDividend）
     * @param today  今日（除息日需 &lt;= today）
     */
    private TreeMap<String, BigDecimal> reinvestDividends(
            TreeMap<String, BigDecimal> closes, List<Map<String, Object>> divs, LocalDate today) {
        if (closes.isEmpty()) return closes;
        String firstKey = closes.firstKey();
        String todayIso = today.toString();

        // exDate!=null 且 exDate<=today 且 exDate>=區間首日，依 exDate 升冪
        List<ExDiv> ex = new ArrayList<>();
        for (Map<String, Object> d : divs) {
            String exDate = asString(d.get("exDividendDate"));
            if (exDate == null || exDate.isBlank()) continue;
            if (exDate.compareTo(todayIso) > 0) continue;
            if (exDate.compareTo(firstKey) < 0) continue;
            ex.add(new ExDiv(exDate, toBigDecimal(d.get("cashDividend")), toBigDecimal(d.get("stockDividend"))));
        }
        ex.sort(Comparator.comparing(ExDiv::exDate));

        BigDecimal shares = BigDecimal.ONE;
        int di = 0;
        TreeMap<String, BigDecimal> adj = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : closes.entrySet()) {
            String date = entry.getKey();
            BigDecimal close = entry.getValue();
            while (di < ex.size() && ex.get(di).exDate().compareTo(date) <= 0) {
                Map.Entry<String, BigDecimal> ce = closes.floorEntry(ex.get(di).exDate());   // 除息日(或之前最近)收盤
                BigDecimal c = ce == null ? null : ce.getValue();
                if (c != null && c.signum() > 0) {
                    BigDecimal stockFactor = BigDecimal.ONE.add(nz(ex.get(di).stock()).divide(BigDecimal.TEN));  // 1 + 配股/10（面額10；美股配股=0）
                    BigDecimal cashFactor = nz(ex.get(di).cash()).divide(c, 12, RoundingMode.HALF_UP);           // 現金/收盤 再投入
                    shares = shares.multiply(stockFactor.add(cashFactor));
                }
                di++;
            }
            adj.put(date, shares.multiply(close));
        }
        return adj;
    }

    private static BigDecimal nz(BigDecimal x) {
        return x == null ? BigDecimal.ZERO : x;
    }

    /** 寬鬆數值解析：null／非數值 → null。 */
    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    // ─────────────────────────── 正規化 ───────────────────────────

    /**
     * union 交易日軸 + forward-fill + 「同起點=0%」報酬率正規化。
     * base=各 series 區間內第一筆非空 close；某日值=(該日或之前最近一筆 close / base −1)×100；首資料日前留 null。
     * totalReturn / asOfDate 直接取該 series 自己最後一筆真實資料（非 forward-fill 尾端），跨市場終點日可不同。
     */
    private Map<String, Object> normalize(List<SeriesRaw> raws) {
        TreeSet<String> axis = new TreeSet<>();
        for (SeriesRaw s : raws) axis.addAll(s.closes().keySet());
        List<String> dates = new ArrayList<>(axis);

        List<Map<String, Object>> series = new ArrayList<>(raws.size());
        for (SeriesRaw s : raws) {
            TreeMap<String, BigDecimal> tree = s.closes();
            Target t = s.target();

            Map<String, Object> so = new LinkedHashMap<>();
            so.put("key", t.key());
            so.put("type", t.type());
            so.put("code", t.code());
            so.put("market", t.market());
            so.put("priceOnly", s.priceOnly());   // 純價格（無含息來源時 true），前端可標示

            BigDecimal base = tree.isEmpty() ? null : tree.firstEntry().getValue();
            boolean validBase = base != null && base.signum() != 0;   // 除零防呆

            List<Object> returns = new ArrayList<>(dates.size());
            for (String d : dates) {
                if (!validBase) { returns.add(null); continue; }
                Map.Entry<String, BigDecimal> e = tree.floorEntry(d);   // 該日或之前最近一筆（forward-fill）
                returns.add(e == null ? null : pct(e.getValue(), base));   // 首資料日前 → null
            }
            so.put("returns", returns);

            if (validBase) {
                Map.Entry<String, BigDecimal> last = tree.lastEntry();
                so.put("totalReturn", pct(last.getValue(), base));
                so.put("asOfDate", last.getKey());
            } else {
                so.put("totalReturn", null);
                so.put("asOfDate", null);
            }
            series.add(so);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dates", dates);
        body.put("series", series);
        return body;
    }

    /** (close / base − 1) × 100，四捨五入到小數 2 位。 */
    private BigDecimal pct(BigDecimal close, BigDecimal base) {
        return close.divide(base, 8, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> emptyBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dates", List.of());
        body.put("series", List.of());
        return body;
    }
}
