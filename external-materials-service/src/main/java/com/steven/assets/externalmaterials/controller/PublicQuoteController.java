package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.PriceCacheReader;
import com.steven.assets.externalmaterials.service.PriceCacheReader.LatestQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 對外（docker host）唯讀查詢最新報價（Requirement 66）。
 *
 * <p>與全庫其餘既有 {@code /internal/*} 端點不同，本 controller 刻意掛在獨立命名空間，
 * 供 docker host 直接呼叫（見 {@code docker-compose.yml} 的 {@code external-materials-service}
 * 服務新增的 {@code ports} 映射，僅綁 {@code 127.0.0.1}）。純讀 Redis 既有快取，
 * 不觸發外部抓取、不寫入任何資料、不需要身份驗證（唯讀公開市場報價 ＋ 僅 loopback，
 * 理由見 {@code spec/requirements.md} Requirement 66）。
 */
@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class PublicQuoteController {

    private final PriceCacheReader reader;

    /** 列出目前 Redis 快取的所有最新報價；market 選填篩選單一市場。查無回空陣列。 */
    @GetMapping
    public List<LatestQuote> list(@RequestParam(required = false) String market) {
        return reader.listAll(market);
    }

    /** 查詢單一標的最新報價；cache miss 回 204 No Content。 */
    @GetMapping("/one")
    public ResponseEntity<LatestQuote> one(
            @RequestParam String code, @RequestParam String market) {
        return reader.findOne(code, market)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
