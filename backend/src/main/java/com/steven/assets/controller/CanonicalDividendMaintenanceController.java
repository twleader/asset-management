package com.steven.assets.controller;

import com.steven.assets.service.CanonicalDividendReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/dividend")
public class CanonicalDividendMaintenanceController {
    private final CanonicalDividendReconciliationService service;
    public CanonicalDividendMaintenanceController(CanonicalDividendReconciliationService service){this.service=service;}
    public record ApplyRequest(String code,String market,String planToken){}
    @GetMapping("/canonical-preview") public CanonicalDividendReconciliationService.Preview preview(@RequestParam String code,@RequestParam String market){return service.preview(code,market);}
    @PostMapping("/canonical-apply") public CanonicalDividendReconciliationService.Applied apply(@RequestBody ApplyRequest request){return service.apply(request.code(),request.market(),request.planToken());}
    @ExceptionHandler(CanonicalDividendReconciliationService.StalePlanException.class)
    public ResponseEntity<?> stale(CanonicalDividendReconciliationService.StalePlanException e){return ResponseEntity.status(409).body(java.util.Map.of("message",e.getMessage()));}
}
