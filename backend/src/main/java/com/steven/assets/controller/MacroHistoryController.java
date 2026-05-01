package com.steven.assets.controller;

import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexYearEndHistory;
import com.steven.assets.repository.TaiwanGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TwseIndexYearEndHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 總體經濟年度時間序列：人均 GDP / 大盤年末收盤。
 * Requirement 18：「GDP + 台股大盤」頁面之資料來源。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MacroHistoryController {

    private final TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    private final TwseIndexYearEndHistoryRepository twseRepo;

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
}
