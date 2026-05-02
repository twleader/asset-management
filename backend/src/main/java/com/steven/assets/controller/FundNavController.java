package com.steven.assets.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.model.Bank;
import com.steven.assets.model.FundMaster;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.FundMasterRepository;
import com.steven.assets.service.FundNavService;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 信託基金主檔 + NAV 操作端點（Requirement 19）。
 *
 * 主檔 CRUD：
 *  - GET    /api/funds                  全部 fund_master（含 inactive，給設定頁用）
 *  - POST   /api/funds                  新增
 *  - PUT    /api/funds/{fundCode}       編輯（fundCode 不可改）
 *  - PATCH  /api/funds/{fundCode}/active  啟用 / 停用
 *
 * NAV：
 *  - POST /api/fund-nav/refresh         proxy 至 external-materials-service
 *  - GET  /api/fund-nav/latest          單支最新 NAV + FX
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class FundNavController {

    private final FundMasterRepository fundMasterRepo;
    private final BankRepository bankRepo;
    private final FundNavService fundNavService;
    private final WebClient externalClient;

    public FundNavController(FundMasterRepository fundMasterRepo,
                             BankRepository bankRepo,
                             FundNavService fundNavService,
                             @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.fundMasterRepo = fundMasterRepo;
        this.bankRepo = bankRepo;
        this.fundNavService = fundNavService;
        this.externalClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    // ───────── fund_master CRUD ─────────

    /** 全部 fund_master（含 inactive）— 設定頁用。SnapshotForm BFF 自行過濾 active。 */
    @GetMapping("/funds")
    public List<FundDto> listFunds() {
        return fundMasterRepo.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/funds")
    public ResponseEntity<FundDto> createFund(@RequestBody CreateFundRequest req) {
        if (req.fundCode() == null || req.fundCode().isBlank())
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "fundCode 必填");
        if (fundMasterRepo.existsById(req.fundCode()))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "fundCode 已存在: " + req.fundCode());
        Bank bank = req.bankId() != null ? bankRepo.findById(req.bankId()).orElse(null) : null;
        FundMaster m = FundMaster.builder()
                .fundCode(req.fundCode().trim())
                .fundName(req.fundName())
                .bank(bank)
                .currency(req.currency())
                .site(req.site())
                .fundclearOrgCode(req.fundclearOrgCode())
                .fundclearFundCode(req.fundclearFundCode())
                .fundclearClassCode(req.fundclearClassCode())
                .active(req.active() == null ? Boolean.TRUE : req.active())
                .build();
        return ResponseEntity.ok(toDto(fundMasterRepo.save(m)));
    }

    @PutMapping("/funds/{fundCode}")
    public ResponseEntity<FundDto> updateFund(@PathVariable String fundCode,
                                              @RequestBody UpdateFundRequest req) {
        FundMaster m = fundMasterRepo.findById(fundCode)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        Bank bank = req.bankId() != null ? bankRepo.findById(req.bankId()).orElse(null) : null;
        m.setFundName(req.fundName());
        m.setBank(bank);
        m.setCurrency(req.currency());
        m.setSite(req.site());
        m.setFundclearOrgCode(req.fundclearOrgCode());
        m.setFundclearFundCode(req.fundclearFundCode());
        m.setFundclearClassCode(req.fundclearClassCode());
        return ResponseEntity.ok(toDto(fundMasterRepo.save(m)));
    }

    @PatchMapping("/funds/{fundCode}/active")
    public ResponseEntity<FundDto> setActive(@PathVariable String fundCode,
                                             @RequestBody Map<String, Boolean> body) {
        FundMaster m = fundMasterRepo.findById(fundCode)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        m.setActive(Boolean.TRUE.equals(body.get("active")));
        return ResponseEntity.ok(toDto(fundMasterRepo.save(m)));
    }

    // ───────── NAV ─────────

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

    @GetMapping("/fund-nav/latest")
    public Map<String, Object> latestNav(@RequestParam String fundCode) {
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

    // ───────── DTOs ─────────

    public record FundDto(
            String fundCode,
            String fundName,
            Long bankId,
            String bankDisplayName,
            String currency,
            String site,
            String fundclearOrgCode,
            String fundclearFundCode,
            String fundclearClassCode,
            Boolean active,
            BigDecimal latestNav,
            LocalDate latestNavDate,
            BigDecimal latestFxRate,
            LocalDate latestFxDate,
            BigDecimal twdPerUnit
    ) {}

    public record CreateFundRequest(
            @NotBlank String fundCode,
            @NotBlank String fundName,
            Long bankId,
            @NotBlank String currency,
            @NotBlank String site,
            @NotBlank String fundclearOrgCode,
            @NotBlank String fundclearFundCode,
            @NotBlank String fundclearClassCode,
            Boolean active
    ) {}

    public record UpdateFundRequest(
            @NotBlank String fundName,
            Long bankId,
            @NotBlank String currency,
            @NotBlank String site,
            @NotBlank String fundclearOrgCode,
            @NotBlank String fundclearFundCode,
            @NotBlank String fundclearClassCode
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
                m.getFundclearOrgCode(),
                m.getFundclearFundCode(),
                m.getFundclearClassCode(),
                m.getActive(),
                latest != null ? latest.nav() : null,
                latest != null ? latest.navDate() : null,
                latest != null ? latest.fxRate() : null,
                latest != null ? latest.fxDate() : null,
                latest != null ? latest.twdPerUnit() : null
        );
    }
}
