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

        // 0000 = 台股大盤（TAIEX）特殊代號：直接回傳，不打外部、不寫 stock 主檔
        if ("0000".equals(upperCode) && "台股".equals(market)) {
            return ResponseEntity.ok(Map.of("stockName", "台股大盤"));
        }
        // 美股無「0000」這個代號；過去若放行會被 Yahoo fuzzy match 回隨機公司（例：Shenzhen 7Road Tech Co Ltd）
        // 然後自動寫入 stock 主檔。直接拒絕，避免污染。
        if ("0000".equals(upperCode) && "美股".equals(market)) {
            return ResponseEntity.ok(Map.of("stockName", ""));
        }

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

    /**
     * 反向查找：依股名查股票代號（只查本地 stock 主檔，精確匹配）。
     *  - 0000 / 「台股大盤」特例：直接回 0000
     *  - 主檔找不到時回空字串，由前端提示使用者改輸入代號
     *
     * 不打外部 API：FinMind / Yahoo 原本就是 code → name 設計，反向查可靠度不足。
     */
    @GetMapping("/lookup-code")
    public ResponseEntity<Map<String, String>> lookupCode(
            @RequestParam String name,
            @RequestParam String market) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return ResponseEntity.ok(Map.of("stockCode", ""));
        }

        if ("台股大盤".equals(trimmed) && "台股".equals(market)) {
            return ResponseEntity.ok(Map.of("stockCode", "0000"));
        }

        String code = stockMasterRepo.findFirstByNameAndMarketOrderByCodeAsc(trimmed, market)
                .map(s -> s.getCode())
                .orElse("");
        return ResponseEntity.ok(Map.of("stockCode", code));
    }
}
