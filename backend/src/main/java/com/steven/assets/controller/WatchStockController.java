package com.steven.assets.controller;

import com.steven.assets.dto.WatchStockDto;
import com.steven.assets.service.AlertChartRenderer;
import com.steven.assets.service.AlertNotificationDispatcher;
import com.steven.assets.service.WatchStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
    private final AlertNotificationDispatcher notificationDispatcher;
    private final AlertChartRenderer chartRenderer;

    @GetMapping
    public List<WatchStockDto.Response> findAll() {
        return service.findAll();
    }

    @PutMapping("/order")
    public ResponseEntity<Void> reorder(@RequestBody List<WatchStockDto.Key> orderedKeys) {
        service.reorder(orderedKeys);
        return ResponseEntity.noContent().build();
    }

    /**
     * 「補發」按鈕：把各市場最後交易日當天觸發的事件彙整成單封 email 重寄（Requirement 23）。
     * 屬本頁動作，走本頁 BFF（/api/bff/watch-stock/resend-digest passthrough）。
     */
    @PostMapping("/resend-digest")
    public ResendDigestResponse resendDigest() {
        AlertNotificationDispatcher.ResendResult r = notificationDispatcher.resendLastTradingDay();
        String message = switch (r.status()) {
            case SENT -> String.format("已補發 %d 檔股票的觸發事件", r.count());
            case NO_EVENTS -> "各市場最後交易日皆無觸發事件，無可補發";
            case NO_RECIPIENTS -> "無啟用中的通知收件人，請先到通知設定新增";
            case EMAIL_DISABLED -> "Email 服務未啟用（未設定 MAIL_USERNAME），無法補發";
        };
        return new ResendDigestResponse(
                r.status() == AlertNotificationDispatcher.ResendStatus.SENT, r.count(), message);
    }

    public record ResendDigestResponse(boolean sent, int count, String message) {}

    /**
     * 走勢圖 PNG 預覽：與警示 email 內嵌的同一張圖（近一年 股價 + 月/季/年線）。
     * 供前端預覽 / 驗證用；資料不足或繪圖失敗回 204。
     */
    @GetMapping(value = "/chart.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> chartPng(@RequestParam String code, @RequestParam String market) {
        return chartRenderer.renderPriceMaPng(code, market)
                .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
