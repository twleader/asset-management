package com.steven.assets.bff.portfolioadvice;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.core.*;import org.springframework.core.annotation.Order;
@RestControllerAdvice(assignableTypes=SrppClaudeAuthBffController.class) @Order(Ordered.HIGHEST_PRECEDENCE)
public class SrppClaudeAuthExceptionAdvice {
 @ExceptionHandler(Exception.class)public ResponseEntity<SrppClaudeAuthDto.ProblemResponse>failure(Exception e){int status=e instanceof SrppClaudeAuthProblem p?p.status:e instanceof org.springframework.web.server.ServerWebInputException?400:503;String code=e instanceof SrppClaudeAuthProblem p?p.code:status==400?"AI_INVALID_REQUEST":"AUTH_FAILED";return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new SrppClaudeAuthDto.ProblemResponse(code,"登入作業無法完成，請重新確認登入狀態"));}
}
