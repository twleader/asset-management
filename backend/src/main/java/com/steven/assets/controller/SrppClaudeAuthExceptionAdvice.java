package com.steven.assets.controller;
import com.steven.assets.service.srpp.settings.SrppClaudeAuthProblem;import com.steven.assets.dto.SrppClaudeAuthDto.ProblemResponse;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.core.*;import org.springframework.core.annotation.Order;
@RestControllerAdvice(assignableTypes=SrppClaudeAuthController.class) @Order(Ordered.HIGHEST_PRECEDENCE)
public class SrppClaudeAuthExceptionAdvice {
 @ExceptionHandler(Exception.class)public ResponseEntity<ProblemResponse>failure(Exception e){int status=e instanceof SrppClaudeAuthProblem p?p.status():e instanceof org.springframework.web.bind.ServletRequestBindingException||e instanceof org.springframework.http.converter.HttpMessageNotReadableException?400:503;String code=e instanceof SrppClaudeAuthProblem p?p.code():status==400?"AI_INVALID_REQUEST":"AUTH_FAILED";return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ProblemResponse(code,"登入作業無法完成，請重新確認登入狀態"));}
}
