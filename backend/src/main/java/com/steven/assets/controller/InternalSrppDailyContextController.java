package com.steven.assets.controller;

import com.steven.assets.srpp.SrppDailyContextReadService;
import com.steven.assets.srpp.SrppProblemCatalog;
import com.steven.assets.srpp.SrppReadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Requirement 163／Task 453.1：container-only 的 SRPP 共用計算結果純唯讀讀取（只供 BFF 呼叫）。
 * owner 只來自 BFF 顯式傳遞的 {@code X-User-*}（CurrentUserContext），不接受任何 owner／email 參數；
 * 參數驗證與判斷全部委派 service；service 只回領域結果，HTTP status／problem body 由本 controller 經
 * {@link SrppProblemCatalog} 對照產生（HTTP 轉換，不含業務判斷）。
 */
@RestController
@RequiredArgsConstructor
public class InternalSrppDailyContextController {
    static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
    static final String CACHE_CONTROL = "private, no-store";

    private final SrppDailyContextReadService service;

    @GetMapping("/internal/public-srpp/daily-context")
    public ResponseEntity<String> dailyContext(
            @RequestParam(required = false) String tradingDate,
            @RequestParam(required = false) String slot,
            @RequestParam(required = false) String policyBundleSha256,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String packageId,
            @RequestParam(required = false) String sourceId) {
        return toResponse(service.read(tradingDate, slot, policyBundleSha256, view, packageId, sourceId));
    }

    /** 領域結果 → HTTP：成功為 200 JSON；problem 依 {@link SrppProblemCatalog} 對照 status 並產生 problem JSON。 */
    static ResponseEntity<String> toResponse(SrppReadResult result) {
        return switch (result) {
            case SrppReadResult.Ok ok -> ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ok.body());
            case SrppReadResult.Problem problem -> problemResponse(problem.code());
        };
    }

    static ResponseEntity<String> problemResponse(String code) {
        return ResponseEntity.status(SrppProblemCatalog.get(code).status())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(PROBLEM_JSON)
                .body(SrppProblemCatalog.body(code));
    }
}
