package com.steven.assets.controller;

import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.TenantAccessException;
import com.steven.assets.security.UnauthenticatedException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(TenantAccessException.class)
    public ProblemDetail handleTenantAccess(TenantAccessException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(AdminRequiredException.class)
    public ProblemDetail handleAdminRequired(AdminRequiredException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /** 請求未帶身分（Requirement 28）→ 401，避免落入下方 500 兜底而誤報為伺服器內部錯誤。 */
    @ExceptionHandler(UnauthenticatedException.class)
    public ProblemDetail handleUnauthenticated(UnauthenticatedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Google Drive remote 未設定／授權失效／設定檔不可用（Requirement 51 / Task 242）→ <b>503</b>。
     *
     * <p>沒有這一條會落到下方的 {@code Exception} 兜底變成 500，而 Drive 目錄瀏覽的要求是
     * 「remote 不可用時回<b>可讀錯誤</b>，不得 500 或回空樹」——空樹會被使用者誤讀為
     * 「Drive 裡沒有資料夾」而以為自己選錯位置。503 的語意也比 500 準確：這是外部相依暫時不可用，
     * 不是伺服器內部錯誤。
     */
    @ExceptionHandler(com.steven.assets.service.RcloneClient.RcloneUnavailableException.class)
    public ProblemDetail handleRcloneUnavailable(
            com.steven.assets.service.RcloneClient.RcloneUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /**
     * Google Drive API 每分鐘查詢配額用盡（Requirement 51 / Task 243）→ <b>429</b>。
     *
     * <p>與上一條的 503 刻意分開：503 是「remote 沒設好或授權失效」，使用者**必須**去處理；
     * 429 是「設定全對，只是這一刻額度滿了」，**稍候重試即可**。混為一談會讓使用者對著一個
     * 自己會好的狀況做無謂的排查（實測發生過：訊息是 rclone 原始的一整段 Google API JSON）。
     */
    @ExceptionHandler(com.steven.assets.service.RcloneClient.RcloneRateLimitedException.class)
    public ProblemDetail handleRcloneRateLimited(
            com.steven.assets.service.RcloneClient.RcloneRateLimitedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    /** 參數白名單驗證失敗（@Validated + @Pattern，Requirement 29）→ 400。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }
}
