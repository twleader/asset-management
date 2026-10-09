package com.steven.assets.controller;
import com.steven.assets.service.srpp.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;

/**
 * SRPP 事件證據與決策的 internal 入口（BFF relay 專用）。
 *
 * <p>Task 484.1：不以 {@code consumes} 宣告媒體型別——不符或語法不合法的 Content-Type 會在 handler mapping／
 * 參數解析階段拋例外，落入全域 {@link GlobalExceptionHandler} 回 500。改由入口自行檢查，並以控制器本地
 * {@code @ExceptionHandler} 統一輸出七欄 RFC 9457 problem（{@code instance} 為該請求的公開路徑）。
 */
@RestController @RequiredArgsConstructor @RequestMapping("/internal/srpp")
public class InternalSrppCaptureController {
 private static final Logger log = LoggerFactory.getLogger(InternalSrppCaptureController.class);
 private final SrppCaptureService service;
 @PostMapping(value="/event-evidence/capture",produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<String> event(@RequestHeader(value=HttpHeaders.CONTENT_TYPE,required=false) String contentType,@RequestBody(required=false) String raw){accept(contentType,raw);return response(service.captureEvent(raw));}
 @PostMapping(value="/daily-decision/evaluate",produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<String> decision(@RequestHeader(value=HttpHeaders.CONTENT_TYPE,required=false) String contentType,@RequestBody(required=false) String raw){accept(contentType,raw);return response(service.evaluate(raw));}
 private static ResponseEntity<String> response(SrppCaptureService.Result r){return ResponseEntity.status(r.status()).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON).body(r.body());}

 /** 只接受 type=application、subtype=json（不分大小寫、忽略參數）；{@code +json}、萬用字元與缺失一律 415，空 body 400。 */
 private static void accept(String contentType,String raw){
  if(!isApplicationJson(contentType))throw new SrppCaptureProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UNSUPPORTED_MEDIA_TYPE");
  if(raw==null||raw.isBlank())throw new SrppCaptureProblem(HttpStatus.BAD_REQUEST,"INVALID_REQUEST");
 }
 static boolean isApplicationJson(String contentType){
  if(contentType==null||contentType.isBlank())return false;
  try{MediaType type=MediaType.parseMediaType(contentType);return "application".equalsIgnoreCase(type.getType())&&"json".equalsIgnoreCase(type.getSubtype());}
  catch(InvalidMediaTypeException e){return false;}
 }

 @ExceptionHandler(SrppCaptureProblem.class) public ResponseEntity<Map<String,Object>> problem(SrppCaptureProblem p,HttpServletRequest request){return problem(p.code,p.errors,request);}
 /** 語法不合法的 Content-Type 在 {@code @RequestBody} 解析階段即拋出，早於方法本體。 */
 @ExceptionHandler({HttpMediaTypeNotSupportedException.class,InvalidMediaTypeException.class}) public ResponseEntity<Map<String,Object>> unsupportedMediaType(Exception ex,HttpServletRequest request){return problem("UNSUPPORTED_MEDIA_TYPE",List.of(),request);}
 /** 未預期例外：本體只用固定文案；伺服器端 log 記錄例外類別與 stack trace（不含 request 內容與帳號）。 */
 @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> unexpected(Exception ex,HttpServletRequest request){
  log.error("SRPP capture 未預期錯誤 instance={} exception={}",instance(request),ex.getClass().getName(),ex);
  return problem("INTERNAL_ERROR",List.of(),request);
 }
 private static ResponseEntity<Map<String,Object>> problem(String code,List<Map<String,Object>> errors,HttpServletRequest request){
  SrppCaptureProblemCatalog.Problem p=SrppCaptureProblemCatalog.get(code);
  Map<String,Object> body=new LinkedHashMap<>();
  body.put("type","about:blank");body.put("title",p.title());body.put("status",p.status());body.put("detail",p.detail());
  body.put("instance",instance(request));body.put("code",p.code());body.put("retryable",p.retryable());
  if(errors!=null&&!errors.isEmpty())body.put("errors",errors);
  return ResponseEntity.status(p.status()).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
 }
 /** internal 路徑對應回 9090 公開路徑；problem 的 instance 一律是呼叫端看得到的那一條。 */
 private static String instance(HttpServletRequest request){
  String uri=request==null?null:request.getRequestURI();
  return uri!=null&&uri.endsWith("/daily-decision/evaluate")?SrppCaptureProblemCatalog.DECISION_INSTANCE:SrppCaptureProblemCatalog.EVENT_INSTANCE;
 }
}
