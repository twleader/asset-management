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
    private final com.steven.assets.service.FundDividendService fundDividendService;
    private final WebClient externalClient;

    public FundNavController(FundMasterRepository fundMasterRepo,
                             BankRepository bankRepo,
                             FundNavService fundNavService,
                             com.steven.assets.service.FundDividendService fundDividendService,
                             @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.fundMasterRepo = fundMasterRepo;
        this.bankRepo = bankRepo;
        this.fundNavService = fundNavService;
        this.fundDividendService = fundDividendService;
        this.externalClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    // ───────── fund_master CRUD ─────────

    /**
     * 全部 fund_master（含 inactive）。
     * 帶 ?date=YYYY-MM-DD 時，每筆 NAV / FX / 配息估算改用該基準日（Requirement 21）。
     */
    @GetMapping("/funds")
    public List<FundDto> listFunds(@RequestParam(required = false) String date) {
        LocalDate basedate = (date == null || date.isBlank()) ? null : LocalDate.parse(date);
        return fundMasterRepo.findAll().stream()
                .map(m -> toDto(m, basedate))
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

    /** 信託基金 NAV 歷史回補 (Requirement 21)，proxy 至 external-materials-service。 */
    @PostMapping(value = "/fund-nav/backfill", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> backfillNav(@RequestParam(defaultValue = "10") int years) {
        return proxyBackfill("/internal/fund-nav/backfill?years=" + years);
    }

    /** 信託基金配息歷史回補 (Requirement 21)。 */
    @PostMapping(value = "/fund-dividend/backfill", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> backfillDividend(@RequestParam(defaultValue = "10") int years) {
        return proxyBackfill("/internal/fund-dividend/backfill?years=" + years);
    }

    private Map<String, Object> proxyBackfill(String path) {
        try {
            JsonNode resp = externalClient.post()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(java.time.Duration.ofMinutes(5));
            if (resp == null) return Map.of("fundOk", 0, "fundFail", 0, "total", 0, "rowsWritten", 0);
            return Map.of(
                    "fundOk", resp.path("fundOk").asInt(0),
                    "fundFail", resp.path("fundFail").asInt(0),
                    "total", resp.path("total").asInt(0),
                    "rowsWritten", resp.path("rowsWritten").asInt(0));
        } catch (Exception e) {
            log.warn("呼叫 external-materials-service {} 失敗: {}", path, e.toString());
            return Map.of("error", e.toString());
        }
    }

    /**
     * 同步全抓信託基金配息歷史 (Requirement 20)。
     */
    @PostMapping(value = "/fund-dividend/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> refreshDividend() {
        try {
            JsonNode resp = externalClient.post()
                    .uri("/internal/fund-dividend/refresh")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return Map.of("success", 0, "failed", 0, "total", 0, "written", 0);
            return Map.of(
                    "success", resp.path("success").asInt(0),
                    "failed", resp.path("failed").asInt(0),
                    "total", resp.path("total").asInt(0),
                    "written", resp.path("written").asInt(0));
        } catch (Exception e) {
            log.warn("呼叫 external-materials-service /internal/fund-dividend/refresh 失敗: {}", e.toString());
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
            BigDecimal twdPerUnit,
            BigDecimal annualDividendPerUnitTwd,  // Requirement 20：近 12 個月加總 × FX
            Integer dividendMonthsCounted          // 統計用，前端可顯示「近 N 個月」
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

    private FundDto toDto(FundMaster m) { return toDto(m, null); }

    private FundDto toDto(FundMaster m, LocalDate basedate) {
        var latest = fundNavService.getNavTwdOnDate(m.getFundCode(), basedate).orElse(null);
        var div = fundDividendService.getAnnualEstimateOnDate(m.getFundCode(), basedate).orElse(null);
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
                latest != null ? latest.twdPerUnit() : null,
                div != null ? div.annualPerUnitTwd() : null,
                div != null ? div.monthsCounted() : null
        );
    }
}
