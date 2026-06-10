package com.steven.assets.controller;

import com.steven.assets.model.KoreaGdpPerCapitaHistory;
import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.TwseIndexYearEndHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.KoreaGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TaiwanGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.TwseIndexYearEndHistoryRepository;
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
 * 總體經濟年度時間序列：人均 GDP / 大盤年末收盤。
 * Requirement 18：「GDP + 台股大盤」頁面之資料來源。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MacroHistoryController {

    /** 美股四大指數合法代碼（道瓊 / 標普500 / 那斯達克綜合 / 費城半導體）。 */
    private static final Set<String> US_INDEX_CODES = Set.of("DJI", "SPX", "IXIC", "SOX");

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    private final KoreaGdpPerCapitaHistoryRepository koreaGdpRepo;
    private final TwseIndexYearEndHistoryRepository twseRepo;
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

    @GetMapping("/twse-year-end-index")
    public List<TwseIndexYearEndHistory> getTwseYearEndIndex(
            @RequestParam(required = false) Integer since) {
        return since == null
                ? twseRepo.findAllByOrderByYearAsc()
                : twseRepo.findByYearGreaterThanEqualOrderByYearAsc(since);
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

    @PostMapping("/twse-year-end-index/refresh")
    public Map<String, Object> refreshTwse(
            @RequestParam(required = false) Integer from,
            @RequestParam(required = false) Integer to) throws Exception {
        int currentYear = LocalDate.now().getYear();
        int f = from == null ? currentYear - 29 : from;
        int t = to == null ? currentYear : to;
        return macroHistoryService.refreshTwseYearEnd(f, t);
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

    /**
     * 最新一個交易日的大盤點位（list 長度 0 或 1）。
     * 供 BFF 在「當年尚未到 12/31」時，把最後一個交易日的 close 當作年末收盤代替值。
     */
    @GetMapping("/twse-daily-index/latest")
    public List<TwseIndexDailyHistory> getTwseDailyLatest() {
        return twseDailyRepo.findTopNByOrderByTradingDateDesc(1);
    }

    /** 美股四大指數每日 OHLC（Requirement 18：日線圖市場切換）。code ∈ {DJI,SPX,IXIC,SOX}。 */
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
}
