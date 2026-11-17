package com.steven.assets.controller;
import com.steven.assets.service.tradereport.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/asset-transactions/report-import") @RequiredArgsConstructor
public class TradeReportImportController {
 private final TradeReportImportService service;
 @PostMapping("/preview") public TradeReportImportService.Response preview(@RequestBody TradeReportImportService.Request request){return service.preview(request);}
 @PostMapping("/apply") public TradeReportImportService.Response apply(@RequestBody TradeReportImportService.Request request){return service.apply(request);}
 @ExceptionHandler(TradeReportParser.Invalid.class) public ResponseEntity<?> invalid(TradeReportParser.Invalid e){return ResponseEntity.badRequest().body(Map.of("code",e.getMessage(),"message","成交回報格式無法驗證"));}
 @ExceptionHandler(TradeReportImportService.Conflict.class) public ResponseEntity<?> conflict(TradeReportImportService.Conflict e){return ResponseEntity.status(409).body(e.response());}
 @ExceptionHandler(TradeReportProblem.class) public ResponseEntity<?> problem(TradeReportProblem e){return ResponseEntity.status(e.status()).body(Map.of("code",e.getMessage(),"message","匯入要求未通過驗證"));}
}
