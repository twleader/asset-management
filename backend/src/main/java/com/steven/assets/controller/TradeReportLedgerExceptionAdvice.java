package com.steven.assets.controller;
import com.steven.assets.service.tradereport.TradeReportProblem;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice(assignableTypes=AssetTransactionController.class) @Order(-1)
public class TradeReportLedgerExceptionAdvice {
 @ExceptionHandler(TradeReportProblem.class) public ResponseEntity<?> handle(TradeReportProblem e){return ResponseEntity.status(e.status()).body(Map.of("code",e.getMessage(),"message","可能與已匯入成交回報重複，請先查核既有交易紀錄"));}
}
