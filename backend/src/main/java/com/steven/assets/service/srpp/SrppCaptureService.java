package com.steven.assets.service.srpp;
import com.steven.assets.service.srpp.decision.DailyDecisionCaptureService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
/** Stable controller facade; all frozen capture/calculation work is delegated. */
@Service
public class SrppCaptureService {
    private final DailyDecisionCaptureService decisions;
    public SrppCaptureService(DailyDecisionCaptureService decisions){this.decisions=decisions;}
    public Result evaluate(String raw){return decisions.evaluate(raw);}
    public record Result(HttpStatus status,String body,boolean replay) {}
}
