package com.steven.assets.controller;

import com.steven.assets.dto.AnnualDividendSummary;
import com.steven.assets.service.AnnualDividendService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/annual-dividends")
public class AnnualDividendController {
    private final AnnualDividendService service;
    public AnnualDividendController(AnnualDividendService service) { this.service = service; }

    @GetMapping
    public AnnualDividendSummary summary(@RequestParam MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(e -> !Set.of("year", "market", "stock").contains(e.getKey())
                || e.getValue() == null || e.getValue().isEmpty()
                || (!"stock".equals(e.getKey()) && e.getValue().size() != 1)))
            throw new IllegalArgumentException("僅接受單一年度、市場與可多選股票參數，不接受指定使用者");
        return service.summary(query.getFirst("year"), query.getFirst("market"), query.get("stock"));
    }
}
