package com.steven.assets.controller;

import com.steven.assets.dto.WatchStockDto;
import com.steven.assets.service.WatchStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 觀察清單 API（v1.22 起改為 stock_alert 衍生 view，無獨立 watch_stock 表）。
 *
 * 路徑保持 /api/watch-stocks 以維持前端 URL 穩定，但語意改變：
 *  - GET /api/watch-stocks            列出觀察清單（去重）
 *  - PUT /api/watch-stocks/order      拖曳重排，body 為 [{stockCode, market}] 陣列
 *
 * 新增觀察改由前端直接呼叫 /api/stock-alerts，不再有 POST /api/watch-stocks。
 * 移除觀察一律在「警示條件」頁刪掉該股票最後一筆 alert，無觀察清單級的 DELETE。
 */
@RestController
@RequestMapping("/api/watch-stocks")
@RequiredArgsConstructor
public class WatchStockController {

    private final WatchStockService service;

    @GetMapping
    public List<WatchStockDto.Response> findAll() {
        return service.findAll();
    }

    @PutMapping("/order")
    public ResponseEntity<Void> reorder(@RequestBody List<WatchStockDto.Key> orderedKeys) {
        service.reorder(orderedKeys);
        return ResponseEntity.noContent().build();
    }
}
