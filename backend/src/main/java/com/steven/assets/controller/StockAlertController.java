package com.steven.assets.controller;

import com.steven.assets.dto.StockAlertDto;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.service.HistoricalDataService;
import com.steven.assets.service.StockAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-alerts")
@RequiredArgsConstructor
public class StockAlertController {

    private final StockAlertService service;
    private final StockRepository stockMasterRepo;
    private final HistoricalDataService historicalDataService;

    @GetMapping
    public List<StockAlertDto.Response> findAll() {
        return service.findAll();
    }

    @PostMapping
    public StockAlertDto.Response create(@RequestBody StockAlertDto.Request req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public StockAlertDto.Response update(@PathVariable Long id, @RequestBody StockAlertDto.Request req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/active")
    public StockAlertDto.Response toggleActive(@PathVariable Long id) {
        return service.toggleActive(id);
    }

    /** 更新排列順序，body 為排序後的 id 陣列 */
    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(@RequestBody List<Long> orderedIds) {
        service.reorder(orderedIds);
        return ResponseEntity.noContent().build();
    }

    /** 手動觸發一次到價警示檢查 */
    @PostMapping("/check")
    public ResponseEntity<Void> triggerCheck() {
        service.checkAlerts();
        return ResponseEntity.noContent().build();
    }

    /**
     * 查詢股票名稱：
     * 1. 先查 stock 主檔
     * 2. 找不到時呼叫外部 API（台股→FinMind，美股→Yahoo）
     * 3. 查到後寫入 stock 主檔供下次使用
     */
    @GetMapping("/lookup-name")
    public ResponseEntity<Map<String, String>> lookupName(
            @RequestParam String code,
            @RequestParam String market) {
        String upperCode = code.trim().toUpperCase();

        // 1. 查本地 stock 主檔
        String name = stockMasterRepo.findByCodeAndMarket(upperCode, market)
                .map(s -> s.getName())
                .orElse("");

        // 2. 若本地找不到，呼叫外部 API
        if (name.isEmpty()) {
            if ("台股".equals(market)) {
                name = historicalDataService.fetchTwStockName(upperCode);
            } else {
                name = historicalDataService.fetchUsStockName(upperCode);
            }
            // 3. 查到後存入主檔，下次直接用本地
            if (!name.isEmpty()) {
                stockMasterRepo.upsert(upperCode, market, name);
            }
        }

        return ResponseEntity.ok(Map.of("stockName", name));
    }
}
