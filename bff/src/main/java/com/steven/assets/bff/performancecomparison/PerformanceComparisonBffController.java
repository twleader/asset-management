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

    /** 抓回並整理成 date→close 的一個標的。 */
    private record SeriesRaw(Target target, TreeMap<String, BigDecimal> closes) {}

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
     * @param range      3m / 6m / 1y / 2y / 5y（預設 1y）
     */
    @GetMapping("/compare")
    public Mono<ResponseEntity<Map<String, Object>>> compare(
            @RequestParam(required = false) String stocks,
            @RequestParam(required = false) String benchmarks,
            @RequestParam(defaultValue = "1y") String range) {

        List<Target> targets = buildTargets(stocks, benchmarks);
        if (targets.isEmpty()) {
            return Mono.just(ResponseEntity.ok(emptyBody()));
        }

        LocalDate to = LocalDate.now();
        LocalDate from = rangeStart(range, to);

        return Flux.fromIterable(targets)
                .flatMap(t -> fetchCloses(t, from, to).map(tree -> new SeriesRaw(t, tree)))
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
            case "3m" -> to.minusMonths(3);
            case "6m" -> to.minusMonths(6);
            case "2y" -> to.minusYears(2);
            case "5y" -> to.minusYears(5);
            default -> to.minusYears(1);   // 1y 及未知值
        };
    }

    // ─────────────────────────── 抓取 ───────────────────────────

    /** 抓單一標的區間日線 → date→close TreeMap；逐標的 timeout + 降級，單線失敗只讓該線消失。 */
    private Mono<TreeMap<String, BigDecimal>> fetchCloses(Target t, LocalDate from, LocalDate to) {
        boolean stock = "stock".equals(t.type());
        String closeKey = stock ? "closePrice" : "closePoint";
        return businessServicesClient.get()
                .uri(uri -> {
                    if (stock) {
                        return uri.path("/api/market-data/history/stock")
                                .queryParam("code", t.code())
                                .queryParam("market", t.market())
                                .queryParam("start", from.toString())
                                .queryParam("end", to.toString())
                                .build();
                    }
                    if ("TWSE".equals(t.code())) {
                        return uri.path("/api/twse-daily-index")
                                .queryParam("from", from.toString())
                                .queryParam("to", to.toString())
                                .build();
                    }
                    return uri.path("/api/us-daily-index")
                            .queryParam("code", t.code())
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString())
                            .build();
                })
                .retrieve().bodyToMono(LIST_MAP)
                .timeout(Duration.ofSeconds(8))
                .onErrorReturn(Collections.emptyList())
                .map(rows -> toTree(rows, closeKey));
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
