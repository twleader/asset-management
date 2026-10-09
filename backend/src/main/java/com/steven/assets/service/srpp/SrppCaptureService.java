package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.repository.*;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.srpp.SrppJcs;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Task 483：SRPP 事件證據與決策 API 的 fail-closed 邊界（無 HTTP client、無快取寫入、無券商操作）。
 *
 * <p>t481／t482 真正實作前，兩個入口在 request 通過嚴格驗證後一律回 503 {@code CONTEXT_NOT_READY}：
 * 不讀、不寫任何 repository，也不 replay 資料庫中既有的佔位列，避免把空結果凍結成不可變 FINAL 收據。
 */
@Service @RequiredArgsConstructor
public class SrppCaptureService {
    private static final Set<String> EVENT_FIELDS=Set.of("ownerEmail","tradingDate","slot","analysisProfile","policyBundleSha256","swaggerSha256");
    private static final Set<String> DECISION_FIELDS=Set.of("ownerEmail","tradingDate","slot","policyBundleSha256","swaggerSha256");
    // events／decisions 暫時只保留依賴、不呼叫任何方法（Task 483.2）；t481、t482 會各自移除或取代。
    private final SrppEventEvidenceRepository events; private final SrppDecisionRunRepository decisions; private final MarketDataService marketData;

    /** 驗證通過後 fail closed：刻意不加 {@code @Transactional}，也不觸碰 {@link #events}。 */
    public Result captureEvent(String raw) { request(raw, EVENT_FIELDS, true); throw new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY"); }
    /** 驗證通過後 fail closed：刻意不加 {@code @Transactional}，也不觸碰 {@link #decisions}。 */
    public Result evaluate(String raw) { request(raw, DECISION_FIELDS, false); throw new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY"); }
    private Request request(String raw, Set<String> fields, boolean event) { JsonNode n; try { n=SrppJcs.parseStrict(raw); } catch(Exception e) { throw new SrppCaptureProblem(HttpStatus.BAD_REQUEST,"INVALID_REQUEST"); } if(!n.isObject()||n.size()!=fields.size()) bad(); n.fieldNames().forEachRemaining(k->{if(!fields.contains(k))bad();}); String owner=text(n,"ownerEmail"),date=text(n,"tradingDate"),slot=text(n,"slot"),policy=text(n,"policyBundleSha256"),swagger=text(n,"swaggerSha256"),profile=event?text(n,"analysisProfile"):"TW_DAILY"; if(owner.isBlank()||!owner.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))bad(); LocalDate parsed;try{parsed=LocalDate.parse(date);}catch(Exception e){throw new SrppCaptureProblem(HttpStatus.BAD_REQUEST,"INVALID_REQUEST");} if(!parsed.equals(LocalDate.now(ZoneId.of("Asia/Taipei")))||!Set.of("09:05","11:40").contains(slot)||!policy.matches("[a-f0-9]{64}")||!swagger.matches("[a-f0-9]{64}")||!"TW_DAILY".equals(profile))bad(); Optional<Boolean> trading=marketData.isTradingDayCachedOnly("台股",parsed); if(trading.isEmpty())throw new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE,"CALENDAR_UNAVAILABLE"); if(!trading.get())throw new SrppCaptureProblem(HttpStatus.CONFLICT,"NON_TRADING_DAY"); return new Request(owner,parsed,slot,profile,policy,swagger); }
    private static void bad(){throw new SrppCaptureProblem(HttpStatus.BAD_REQUEST,"INVALID_REQUEST");}
    private static String text(JsonNode n,String f){JsonNode v=n.get(f);return v!=null&&v.isTextual()?v.textValue().trim():"";}
    private record Request(String owner,LocalDate date,String slot,String profile,String policy,String swagger){} public record Result(HttpStatus status,String body,boolean replay){}
}
