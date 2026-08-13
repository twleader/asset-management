package com.steven.assets.controller;

import com.steven.assets.service.MalformedUsdTwdRateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只處理 USD/TWD internal live payload 的 typed malformed 錯誤。 */
@RestControllerAdvice(assignableTypes = UsdTwdLiveRateController.class)
public class UsdTwdLiveRateExceptionAdvice {

    @ExceptionHandler(MalformedUsdTwdRateException.class)
    public ResponseEntity<ProblemDetail> handleMalformed(MalformedUsdTwdRateException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "USD/TWD live 資料暫時不可用");
        problem.setTitle("Malformed USD/TWD live data");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }
}
