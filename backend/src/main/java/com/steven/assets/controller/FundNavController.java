package com.steven.assets.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.model.FundMaster;
import com.steven.assets.repository.FundMasterRepository;
import com.steven.assets.service.FundNavService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 信託基金主檔 + NAV 操作端點（Requirement 19）。
 * - GET  /api/funds                列出 fund_master（active）
 * - POST /api/fund-nav/refresh     proxy 至 external-materials-service /internal/fund-nav/refresh
 * - GET  /api/fund-nav/latest      查最新 NAV + FX（給前端預覽用）
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class FundNavController {

    private final FundMasterRepository fundMasterRepo;
    private final FundNavService fundNavService;
    private final WebClient externalClient;

    public FundNavController(FundMasterRepository fundMasterRepo,
                             FundNavService fundNavService,
                             @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.fundMasterRepo = fundMasterRepo;
        this.fundNavService = fundNavService;
        this.externalClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    /** fund_master 列表，供前端 dropdown / SnapshotForm 預載。 */
    @GetMapping("/funds")
    public List<FundDto> listFunds() {
        return fundMasterRepo.findByActiveTrue().stream()
                .map(this::toDto)
                .toList();
    }

    /** 觸發 external-materials-service 立即抓取所有基金最新 NAV。 */
    @PostMapping(value = "/fund-nav/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> refreshNav() {
        try {
            JsonNode resp = externalClient.post()
                    .uri("/internal/fund-nav/refresh")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return Map.of("success", 0, "failed", 0, "total", 0);
            return Map.of(
                    "success", resp.path("success").asInt(0),
                    "failed", resp.path("failed").asInt(0),
                    "total", resp.path("total").asInt(0));
        } catch (Exception e) {
            log.warn("呼叫 external-materials-service /internal/fund-nav/refresh 失敗: {}", e.toString());
            return Map.of("error", e.toString());
        }
    }

    /** 單支基金的最新 NAV + FX 資訊。 */
    @GetMapping("/fund-nav/latest")
    public Map<String, Object> latestNav(@org.springframework.web.bind.annotation.RequestParam String fundCode) {
        return fundNavService.getLatestNavTwd(fundCode)
                .map(l -> Map.<String, Object>of(
                        "fundCode", l.fundCode(),
                        "currency", l.currency(),
                        "nav", l.nav(),
                        "navDate", l.navDate(),
                        "fxRate", l.fxRate(),
                        "fxDate", l.fxDate(),
                        "twdPerUnit", l.twdPerUnit()))
                .orElse(Map.of("fundCode", fundCode, "available", false));
    }

    public record FundDto(
            String fundCode,
            String fundName,
            Long bankId,
            String bankDisplayName,
            String currency,
            String site,
            BigDecimal latestNav,
            LocalDate latestNavDate,
            BigDecimal latestFxRate,
            LocalDate latestFxDate,
            BigDecimal twdPerUnit
    ) {}

    private FundDto toDto(FundMaster m) {
        var latest = fundNavService.getLatestNavTwd(m.getFundCode()).orElse(null);
        return new FundDto(
                m.getFundCode(),
                m.getFundName(),
                m.getBank() != null ? m.getBank().getId() : null,
                m.getBank() != null ? m.getBank().getDisplayName() : null,
                m.getCurrency(),
                m.getSite(),
                latest != null ? latest.nav() : null,
                latest != null ? latest.navDate() : null,
                latest != null ? latest.fxRate() : null,
                latest != null ? latest.fxDate() : null,
                latest != null ? latest.twdPerUnit() : null
        );
    }
}
