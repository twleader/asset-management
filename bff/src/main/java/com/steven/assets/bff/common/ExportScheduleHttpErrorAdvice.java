package com.steven.assets.bff.common;

import com.steven.assets.bff.assethistory.AssetHistoryBffController;
import com.steven.assets.bff.realizedgain.RealizedGainBffController;
import com.steven.assets.bff.commodityprice.CommodityPriceBffController;
import com.steven.assets.bff.exchangerate.ExchangeRateBffController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Keep the refresh instruction in retired schedule responses independently of generic error settings. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AssetHistoryBffController.class, RealizedGainBffController.class,
        CommodityPriceBffController.class, ExchangeRateBffController.class})
public class ExportScheduleHttpErrorAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> explicitStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(
                ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason()));
    }
}
