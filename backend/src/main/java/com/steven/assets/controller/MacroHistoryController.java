package com.steven.assets.controller;

import com.steven.assets.model.KoreaGdpPerCapitaHistory;
import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexYearEndHistory;
import com.steven.assets.repository.KoreaGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TaiwanGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TwseIndexYearEndHistoryRepository;
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

/**
 * 總體經濟年度時間序列：人均 GDP / 大盤年末收盤。
 * Requirement 18：「GDP + 台股大盤」頁面之資料來源。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MacroHistoryController {

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    private final KoreaGdpPerCapitaHistoryRepository koreaGdpRepo;
    private final TwseIndexYearEndHistoryRepository twseRepo;
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
}
