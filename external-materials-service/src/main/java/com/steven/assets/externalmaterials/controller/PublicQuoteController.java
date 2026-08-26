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
 * asset-net 內原始最新報價唯讀 reader（Requirement 66）。
 *
 * <p>與全庫其餘既有 {@code /internal/*} 端點不同，本 controller 刻意掛在獨立命名空間，
 * 供 gateway 後的既有 BFF public aggregation 讀取。Docker host 僅能經
 * {@code 127.0.0.1:9090 -> api-gateway -> BFF} 存取同名公開端點；本 service 沒有 host ports
 * 映射。純讀 Redis 既有快取，不觸發外部抓取、不寫入任何資料、不需要身份驗證（公開市場資料，
 * 理由見 {@code spec/requirements.md} Requirement 66／108）。
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
