package com.steven.assets.controller;

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
import com.steven.assets.service.MacroHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 股市分析頁（Requirement 18）資料來源：台/日/韓人均 GDP、台股大盤日線、海外指數日線、指數當日分時。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MacroHistoryController {

    /** 海外指數合法代碼（當日分時 refresh 守門）；單一清單由 {@link MacroHistoryService#OVERSEAS_INDEX_CODES} 提供（與自動回補排程共用）。 */
    private static final Set<String> US_INDEX_CODES = Set.copyOf(MacroHistoryService.OVERSEAS_INDEX_CODES);

    /**
     * 日線回補 refresh 守門集合：純價格海外指數 ∪ 含息報酬指數（SP500TR）。
     * 讓績效比較頁（Requirement 33）的 SP500TR 也能經 /us-daily-index/refresh 觸發 Yahoo ^SP500TR 回補。
     */
    private static final Set<String> US_INDEX_REFRESH_CODES = java.util.stream.Stream
            .concat(MacroHistoryService.OVERSEAS_INDEX_CODES.stream(),
                    MacroHistoryService.TOTAL_RETURN_US_INDEX_CODES.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    private final JapanGdpPerCapitaHistoryRepository japanGdpRepo;
    private final KoreaGdpPerCapitaHistoryRepository koreaGdpRepo;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;
    private final UsIndexDailyHistoryRepository usDailyRepo;
    private final MacroHistoryService macroHistoryService;

    @GetMapping("/taiwan-gdp")
    public List<TaiwanGdpPerCapitaHistory> getTaiwanGdp(
            @RequestParam(required = false) Integer since) {
        return since == null
                ? gdpRepo.findAllByOrderByYearAsc()
                : gdpRepo.findByYearGreaterThanEqualOrderByYearAsc(since);
    }

    @PostMapping("/taiwan-gdp/refresh-from-imf")
    public Map<String, Object> refreshGdpFromImf() throws Exception {
        return macroHistoryService.refreshGdpFromImf();
    }

    @GetMapping("/japan-gdp")
    public List<JapanGdpPerCapitaHistory> getJapanGdp(
            @RequestParam(required = false) Integer since) {
        return since == null
                ? japanGdpRepo.findAllByOrderByYearAsc()
                : japanGdpRepo.findByYearGreaterThanEqualOrderByYearAsc(since);
    }

    @PostMapping("/japan-gdp/refresh-from-imf")
    public Map<String, Object> refreshJapanGdpFromImf() throws Exception {
        return macroHistoryService.refreshJapanGdpFromImf();
    }

    @GetMapping("/korea-gdp")
    public List<KoreaGdpPerCapitaHistory> getKoreaGdp(
            @RequestParam(required = false) Integer since) {
        return since == null
                ? koreaGdpRepo.findAllByOrderByYearAsc()
                : koreaGdpRepo.findByYearGreaterThanEqualOrderByYearAsc(since);
    }

    @PostMapping("/korea-gdp/refresh-from-imf")
    public Map<String, Object> refreshKoreaGdpFromImf() throws Exception {
        return macroHistoryService.refreshKoreaGdpFromImf();
    }

    @GetMapping("/twse-daily-index")
    public List<TwseIndexDailyHistory> getTwseDaily(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        if (from != null && to != null) {
            return twseDailyRepo.findByTradingDateBetweenOrderByTradingDateAsc(from, to);
        }
        if (from != null) {
            return twseDailyRepo.findByTradingDateGreaterThanEqualOrderByTradingDateAsc(from);
        }
        return twseDailyRepo.findAllByOrderByTradingDateAsc();
    }

    @PostMapping("/twse-daily-index/refresh")
    public Map<String, Object> refreshTwseDaily(
            @RequestParam(defaultValue = "10") int years) {
        return macroHistoryService.refreshTwseDaily(years);
    }

    /** 海外指數每日 OHLC（Requirement 18：日線圖市場切換）。code ∈ {DJI,SPX,IXIC,SOX,FTSE,DAX,KOSPI,N225}。 */
    @GetMapping("/us-daily-index")
    public List<UsIndexDailyHistory> getUsDaily(
            @RequestParam String code,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        if (from != null && to != null) {
            return usDailyRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(code, from, to);
        }
        if (from != null) {
            return usDailyRepo.findByIndexCodeAndTradingDateGreaterThanEqualOrderByTradingDateAsc(code, from);
        }
        return usDailyRepo.findByIndexCodeOrderByTradingDateAsc(code);
    }

    @PostMapping("/us-daily-index/refresh")
    public Map<String, Object> refreshUsDaily(@RequestParam String code) {
        if (!US_INDEX_REFRESH_CODES.contains(code)) {
            return Map.of("error", "未知指數代碼: " + code, "upserted", 0);
        }
        return macroHistoryService.refreshUsIndexDaily(code);
    }

    /**
     * TWSE 發行量加權股價「報酬指數」（含息）近 N 年背景回補（績效比較頁 Requirement 33）。
     * 立即回 {"started":true,"pending":&lt;n&gt;}，實際抓取在背景執行。
     * POST /api/twse-daily-index/refresh-tr?years=10
     */
    @PostMapping("/twse-daily-index/refresh-tr")
    public Map<String, Object> refreshTwseReturnIndex(
            @RequestParam(defaultValue = "10") int years) {
        return macroHistoryService.refreshTwseReturnIndex(years);
    }

    /** 指數「當日」分時走勢（Yahoo 5m，最新交易日；transient）。market ∈ {TWSE,DJI,SPX,IXIC,SOX,FTSE,DAX,KOSPI,N225}。 */
    @GetMapping("/index-intraday")
    public List<MacroHistoryService.IntradayPoint> getIndexIntraday(@RequestParam String market) {
        // 資安（Requirement 29）：market 會流入 external-materials-service 打 Yahoo；以已知指數白名單擋參數注入
        if (!"TWSE".equals(market) && !US_INDEX_CODES.contains(market)) {
            throw new IllegalArgumentException("未知指數代碼: " + market);
        }
        return macroHistoryService.fetchIndexIntraday(market);
    }
}
