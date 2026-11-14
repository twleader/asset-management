package com.steven.assets.controller;

import com.steven.assets.service.srpp.settings.SrppSettingsProblem;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;
import com.steven.assets.dto.SrppSettingsDto.ProblemResponse;

@RestControllerAdvice(assignableTypes=SrppSettingsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SrppSettingsExceptionAdvice {
    @ExceptionHandler(SrppSettingsProblem.class)
    public ResponseEntity<ProblemResponse> problem(SrppSettingsProblem p){return ResponseEntity.status(p.status()).body(new ProblemResponse(p.code(),p.getMessage()));}
}
