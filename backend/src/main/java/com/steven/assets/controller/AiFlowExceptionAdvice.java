package com.steven.assets.controller;

import com.steven.assets.dto.aiflow.AiFlowDtos.Problem;
import com.steven.assets.service.aiflow.AiFlowProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = {AiFlowController.class, SrppAiFlowController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AiFlowExceptionAdvice {
    @ExceptionHandler(AiFlowProblem.class)
    public ResponseEntity<Problem> problem(AiFlowProblem failure, HttpServletRequest request) {
        return response(failure, request);
    }
    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, InvalidMediaTypeException.class})
    public ResponseEntity<Problem> media(Exception ignored, HttpServletRequest request) {
        return response(new AiFlowProblem("UNSUPPORTED_MEDIA_TYPE"), request);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> unreadable(Exception ignored, HttpServletRequest request) {
        return response(new AiFlowProblem("INVALID_REQUEST"), request);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> unexpected(Exception ignored, HttpServletRequest request) {
        return response(new AiFlowProblem("INTERNAL_ERROR"), request);
    }
    private static ResponseEntity<Problem> response(AiFlowProblem failure, HttpServletRequest request) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(failure.body(request.getRequestURI()));
    }
}
