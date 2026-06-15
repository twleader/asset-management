package com.steven.assets.controller;

import com.steven.assets.model.KoreaGdpPerCapitaHistory;
import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
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
 * 股市分析頁（Requirement 18）資料來源：台/韓人均 GDP、台股大盤日線、海外指數日線、指數當日分時。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MacroHistoryController {

    /**
     * 海外指數合法代碼（refresh 守門）。
     * 美股四大：道瓊 / 標普500 / 那斯達克綜合 / 費城半導體；海外主要：英國富時100 / 德國DAX / 韓國KOSPI / 日經225。
     */
    private static final Set<String> US_INDEX_CODES =
            Set.of("DJI", "SPX", "IXIC", "SOX", "FTSE", "DAX", "KOSPI", "N225");

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
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
        if (!US_INDEX_CODES.contains(code)) {
            return Map.of("error", "未知指數代碼: " + code, "upserted", 0);
        }
        return macroHistoryService.refreshUsIndexDaily(code);
    }

    /** 指數「當日」分時走勢（Yahoo 5m，最新交易日；transient）。market ∈ {TWSE,DJI,SPX,IXIC,SOX,FTSE,DAX,KOSPI,N225}。 */
    @GetMapping("/index-intraday")
    public List<MacroHistoryService.IntradayPoint> getIndexIntraday(@RequestParam String market) {
        return macroHistoryService.fetchIndexIntraday(market);
    }
}
